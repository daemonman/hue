/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.hue.livy.repl.scala.SparkInterpreter

import java.io._

import com.cloudera.hue.livy.repl.Interpreter
import com.cloudera.hue.livy.sessions._
import org.apache.spark.rdd.RDD
import org.apache.spark.repl.SparkIMain
import org.apache.spark.{SparkConf, SparkContext}
import org.json4s.JsonAST._
import org.json4s.{DefaultFormats, Extraction}

import scala.concurrent.{Future, ExecutionContext}
import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.{JPrintWriter, Results}


object SparkInterpreter {
  private val MAGIC_REGEX = "^%(\\w+)\\W*(.*)".r
}

sealed abstract class ExecuteResponse(executeCount: Int)
case class ExecuteComplete(executeCount: Int, output: String) extends ExecuteResponse(executeCount)
case class ExecuteIncomplete(executeCount: Int, output: String) extends ExecuteResponse(executeCount)
case class ExecuteError(executeCount: Int, output: String) extends ExecuteResponse(executeCount)
case class ExecuteMagic(executeCount: Int, content: JValue) extends ExecuteResponse(executeCount)

class SparkInterpreter extends Interpreter {
  import SparkInterpreter._

  private implicit def executor: ExecutionContext = ExecutionContext.global
  private implicit def formats = DefaultFormats

  private var _state: State = NotStarted()
  private val outputStream = new ByteArrayOutputStream()
  private var sparkIMain: SparkIMain = _
  private var sparkContext: SparkContext = _
  private var executeCount = 0

  @Override
  def state = _state

  def start() = {
    require(_state == NotStarted() && sparkIMain == null)

    _state = Starting()

    class InterpreterClassLoader(classLoader: ClassLoader) extends ClassLoader(classLoader) {}
    val classLoader = new InterpreterClassLoader(classOf[SparkInterpreter].getClassLoader)

    val settings = new Settings()
    settings.usejavacp.value = true

    sparkIMain = createSparkIMain(classLoader, settings)
    sparkIMain.initializeSynchronous()

    val sparkConf = new SparkConf(true)
      .setAppName("Livy Spark shell")
      .set("spark.repl.class.uri", sparkIMain.classServerUri)

    sparkContext = new SparkContext(sparkConf)

    sparkIMain.beQuietDuring {
      sparkIMain.bind("sc", "org.apache.spark.SparkContext", sparkContext, List("""@transient"""))
    }

    _state = Idle()
  }

  private def getMaster(): String = {
    sys.props.get("spark.master").getOrElse("local[*]")
  }

  private def createSparkIMain(classLoader: ClassLoader, settings: Settings) = {
    val out = new JPrintWriter(outputStream, true)
    val cls = classLoader.loadClass(classOf[SparkIMain].getName)
    val constructor = cls.getConstructor(classOf[Settings], classOf[JPrintWriter], java.lang.Boolean.TYPE)
    constructor.newInstance(settings, out, false: java.lang.Boolean).asInstanceOf[SparkIMain]
  }

  private def executeMagic(magic: String, rest: String): ExecuteResponse = {
    magic match {
      case "json" => executeJsonMagic(rest)
      case "table" => executeTableMagic(rest)
      case _ =>
        ExecuteError(executeCount, f"Unknown magic command $magic")
    }
  }

  private def executeJsonMagic(name: String): ExecuteResponse = {
    sparkIMain.valueOfTerm(name) match {
      case Some(value) =>
        ExecuteMagic(
          executeCount,
          Extraction.decompose(Map(
            "application/json" -> value
          ))
        )
      case None =>
        ExecuteError(executeCount, f"Value $name does not exist")
    }
  }

  private class TypesDoNotMatch extends Exception

  private def convertTableType(value: JValue): String = {
    value match {
      case (JNothing | JNull) => "NULL_TYPE"
      case JBool(_) => "BOOLEAN_TYPE"
      case JString(_) => "STRING_TYPE"
      case JInt(_) => "BIGINT_TYPE"
      case JDouble(_) => "DOUBLE_TYPE"
      case JDecimal(_) => "DECIMAL_TYPE"
      case JArray(arr) =>
        if (allSameType(arr.iterator)) {
          "ARRAY_TYPE"
        } else {
          throw new TypesDoNotMatch
        }
      case JObject(obj) =>
        if (allSameType(obj.iterator.map(_._2))) {
          "MAP_TYPE"
        } else {
          throw new TypesDoNotMatch
        }
    }
  }

  private def allSameType(values: Iterator[JValue]): Boolean = {
    if (values.hasNext) {
      val type_name = convertTableType(values.next())
      values.forall { case value => type_name.equals(convertTableType(value)) }
    } else {
      true
    }
  }

  private def executeTableMagic(name: String): ExecuteResponse = {
    try {
      sparkIMain.valueOfTerm(name) match {
        case None =>
          ExecuteError(executeCount, f"Value $name does not exist")
        case Some(obj: RDD[_]) =>
          extractTableFromJValue(Extraction.decompose(
            obj.asInstanceOf[RDD[_]].take(10)))
        case Some(obj) =>
          extractTableFromJValue(Extraction.decompose(obj))
      }
    } catch {
      case _: Throwable =>
        ExecuteError(executeCount, "Failed to convert value into a table")
    }
  }

  private def extractTableFromJValue(value: JValue) = {
    // Convert the value into JSON and map it to a table.
    val rows: List[JValue] = value match {
      case JArray(arr) => arr
      case _ => List(value)
    }

    try {
      val headers = scala.collection.mutable.Map[String, Map[String, String]]()

      val data = rows.map { case row =>
        val cols: List[JField] = row match {
          case JArray(arr: List[JValue]) =>
            arr.zipWithIndex.map { case (v, index) => JField(index.toString, v) }
          case JObject(obj) => obj.sortBy(_._1)
          case value: JValue => List(JField("0", value))
        }

        cols.map { case (name, value) =>
          val typeName = convertTableType(value)

          headers.get(name) match {
            case Some(header) =>
              if (header.get("type").get != typeName) {
                throw new TypesDoNotMatch
              }
            case None =>
              headers.put(name, Map(
                "name" -> name,
                "type" -> typeName
              ))
          }

          value
        }
      }

      ExecuteMagic(
        executeCount,
        Extraction.decompose(Map(
          "application/vnd.livy.table.v1+json" -> Map(
            "headers" -> headers.toSeq.sortBy(_._1).map(_._2),
            "data" -> data
          )
        ))
      )
    } catch {
      case _: TypesDoNotMatch =>
        ExecuteError(
          executeCount,
          "table rows have different types"
        )
    }
  }

  @Override
  def execute(code: String): Future[JValue] = {
    executeCount += 1

    synchronized {
      _state = Busy()
    }

    Future {
      val result = executeLines(code.trim.split("\n").toList, ExecuteComplete(executeCount, ""))

      val response = result match {
        case ExecuteComplete(executeCount, output) =>
          Map(
            "status" -> "ok",
            "execution_count" -> executeCount,
            "data" -> Map(
              "text/plain" -> output
            )
          )
        case ExecuteMagic(executeCount, content) =>
          Map(
            "status" -> "ok",
            "execution_count" -> executeCount,
            "data" -> content
          )
        case ExecuteIncomplete(executeCount, output) =>
          Map(
            "status" -> "error",
            "execution_count" -> executeCount,
            "ename" -> "Error",
            "evalue" -> output
          )
        case ExecuteError(executeCount, output) =>
          Map(
            "status" -> "error",
            "execution_count" -> executeCount,
            "ename" -> "Error",
            "evalue" -> output
          )
      }

      _state = Idle()

      Extraction.decompose(response)
    }
  }

  private def executeLines(lines: List[String], result: ExecuteResponse): ExecuteResponse = {
    lines match {
      case Nil => result
      case head :: tail =>
        val result = executeLine(head)

        result match {
          case ExecuteIncomplete(_, _) =>
            tail match {
              case Nil => result
              case next :: nextTail => executeLines(head + "\n" + next :: nextTail, result)
            }
          case ExecuteError(_, _) =>
            result

          case _ =>
            executeLines(tail, result)
        }
    }
  }

  private def executeLine(code: String) = {
    code match {
      case MAGIC_REGEX(magic, rest) =>
        executeMagic(magic, rest)
      case _ =>
        scala.Console.withOut(outputStream) {
          sparkIMain.interpret(code) match {
            case Results.Success => ExecuteComplete(executeCount - 1, readStdout())
            case Results.Incomplete => ExecuteIncomplete(executeCount - 1, readStdout())
            case Results.Error => ExecuteError(executeCount - 1, readStdout())
          }
        }
    }
  }

  private def readStdout() = {
    val output = outputStream.toString("UTF-8").trim
    outputStream.reset()

    output
  }

  @Override
  def close(): Unit = synchronized {
    _state = ShuttingDown()

    if (sparkContext != null) {
      sparkContext.stop()
    }

    if (sparkIMain != null) {
      sparkIMain.close()
      sparkIMain = null
    }

    _state = Dead()
  }
}
