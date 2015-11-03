package controllers

import java.util.concurrent.TimeUnit

import com.kakao.s2graph.core.utils.logger
import play.api.libs.json._
import play.api.test.Helpers._
import play.api.test.{FakeApplication, FakeRequest}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Random

class StrongLabelDeleteSpec extends SpecCommon {
  init()

  def bulkEdges(startTs: Int = 0) = Seq(
    Seq(startTs + 1, "insert", "e", "0", "1", testLabelName2, s"""{"time": 10}""").mkString("\t"),
    Seq(startTs + 2, "insert", "e", "0", "1", testLabelName2, s"""{"time": 11}""").mkString("\t"),
    Seq(startTs + 3, "insert", "e", "0", "1", testLabelName2, s"""{"time": 12}""").mkString("\t"),
    Seq(startTs + 4, "insert", "e", "0", "2", testLabelName2, s"""{"time": 10}""").mkString("\t"),
    Seq(startTs + 5, "insert", "e", "10", "20", testLabelName2, s"""{"time": 10}""").mkString("\t"),
    Seq(startTs + 6, "insert", "e", "10", "21", testLabelName2, s"""{"time": 11}""").mkString("\t"),
    Seq(startTs + 7, "insert", "e", "11", "20", testLabelName2, s"""{"time": 12}""").mkString("\t"),
    Seq(startTs + 8, "insert", "e", "12", "20", testLabelName2, s"""{"time": 13}""").mkString("\t")
  ).mkString("\n")

  def query(id: Long, serviceName: String = testServiceName, columnName: String = testColumnName,
            labelName: String = testLabelName2, direction: String = "out") = Json.parse(
    s"""
        { "srcVertices": [
          { "serviceName": "$serviceName",
            "columnName": "$columnName",
            "id": $id
           }],
          "steps": [
          [ {
              "label": "$labelName",
              "direction": "${direction}",
              "offset": 0,
              "limit": -1,
              "duplicate": "raw"
            }
          ]]
        }""")

  def getEdges(queryJson: JsValue): JsValue = {
    val ret = route(FakeRequest(POST, "/graphs/getEdges").withJsonBody(queryJson)).get
    contentAsJson(ret)
  }

  def getDegree(jsValue: JsValue): Long = {
    ((jsValue \ "degrees") \\ "_degree").headOption.map(_.as[Long]).getOrElse(0L)
  }


//  "strong label delete test" should {
//    running(FakeApplication()) {
//      // insert bulk and wait ..
//      val jsResult = contentAsJson(EdgeController.mutateAndPublish(bulkEdges(), withWait = true))
//      Thread.sleep(asyncFlushInterval)
//    }
//
//    "test strong consistency select" in {
//      running(FakeApplication()) {
//        var result = getEdges(query(0))
//        println(result)
//        (result \ "results").as[List[JsValue]].size must equalTo(2)
//        result = getEdges(query(10))
//        println(result)
//        (result \ "results").as[List[JsValue]].size must equalTo(2)
//        true
//      }
//    }
//
//    "test strong consistency duration. insert -> delete -> insert" in {
//      running(FakeApplication()) {
//        val ts0 = 1
//        val ts1 = 2
//        val ts2 = 3
//
//        val edges = Seq(
//          Seq(5, "insert", "edge", "-10", "-20", testLabelName2).mkString("\t"),
//          Seq(10, "delete", "edge", "-10", "-20", testLabelName2).mkString("\t"),
//          Seq(20, "insert", "edge", "-10", "-20", testLabelName2).mkString("\t")
//        ).mkString("\n")
//
//        val jsResult = contentAsJson(EdgeController.mutateAndPublish(edges, withWait = true))
//
//        Thread.sleep(asyncFlushInterval)
//        val result = getEdges(query(-10))
//
//        println(result)
//
//        true
//      }
//    }
//
//    "test strong consistency deleteAll" in {
//      running(FakeApplication()) {
//        val deletedAt = 100
//        var result = getEdges(query(20, direction = "in", columnName = testTgtColumnName))
//        println(result)
//        (result \ "results").as[List[JsValue]].size must equalTo(3)
//
//
//
//        val json = Json.arr(Json.obj("label" -> testLabelName2,
//          "direction" -> "in", "ids" -> Json.arr("20"), "timestamp" -> deletedAt))
//        println(json)
//        EdgeController.deleteAllInner(json)
//        Thread.sleep(asyncFlushInterval)
//
//
//        result = getEdges(query(11, direction = "out"))
//        println(result)
//        (result \ "results").as[List[JsValue]].size must equalTo(0)
//
//        result = getEdges(query(12, direction = "out"))
//        println(result)
//        (result \ "results").as[List[JsValue]].size must equalTo(0)
//
//        result = getEdges(query(10, direction = "out"))
//        println(result)
//        // 10 -> out -> 20 should not be in result.
//        (result \ "results").as[List[JsValue]].size must equalTo(1)
//        (result \\ "to").size must equalTo(1)
//        (result \\ "to").head.as[String] must equalTo("21")
//
//
//        result = getEdges(query(20, direction = "in", columnName = testTgtColumnName))
//        println(result)
//        (result \ "results").as[List[JsValue]].size must equalTo(0)
//
//        val jsResult = contentAsJson(EdgeController.mutateAndPublish(bulkEdges(startTs = deletedAt + 1), withWait = true))
//        Thread.sleep(asyncFlushInterval)
//
//        result = getEdges(query(20, direction = "in", columnName = testTgtColumnName))
//        println(result)
//        (result \ "results").as[List[JsValue]].size must equalTo(3)
//
//        true
//
//      }
//    }
//  }


  "labelargeSet of contention" should {
    val labelName = testLabelName2
    val maxTgtId = 5
    val batchSize = 100
    val testNum = 20
    val numOfBatch = 10
    var lastTs = 0L

    def testInner(src: Long) = {
      val labelName = testLabelName2
      val lastOps = Array.fill(maxTgtId)("none")

      val allRequests = for {
        ith <- (0 until numOfBatch)
        jth <- (0 until batchSize)
      } yield {
          val currentTs = System.currentTimeMillis() + ith * batchSize + jth
          if (currentTs > lastTs) lastTs = currentTs
          else logger.error(s"Invalid TS: $currentTs > $lastTs")

          val tgt = Random.nextInt(maxTgtId)
          val op = if (Random.nextDouble() < 0.5) "delete" else "update"
          lastOps(tgt) = op
          Seq(currentTs, op, "e", src, tgt, labelName, "{}").mkString("\t")
        }

      val futures = Random.shuffle(allRequests).grouped(batchSize).map { bulkRequest =>
        val bulkEdge = bulkRequest.mkString("\n")
        EdgeController.mutateAndPublish(bulkEdge, withWait = true)
      }

      Await.result(Future.sequence(futures), Duration(20, TimeUnit.MINUTES))

      val expectedDegree = lastOps.count(op => op != "delete" && op != "none")
      val queryJson = query(id = src)
      val result = getEdges(queryJson)
      val resultSize = (result \ "size").as[Long]
      val resultDegree = getDegree(result)

      println(lastOps.toList)
      println(result)

      val ret = resultDegree == expectedDegree && resultSize == resultDegree
      if (!ret) System.err.println(s"[Contention Failed]: $resultDegree, $expectedDegree")

      (ret, lastTs)
    }

    "update delete" in {
      running(FakeApplication()) {
        val ret = for {
          i <- (0 until testNum)
        } yield {
            Thread.sleep(asyncFlushInterval)
            val src = System.currentTimeMillis()

            val (ret, last) = testInner(src)
            ret must beEqualTo(true)
            ret
          }

        ret.forall(identity)
      }
    }

    "update delete 2" in {
      running(FakeApplication()) {
        val src = System.currentTimeMillis()
        var deletedAt = 0L

        val ret = for {
          i <- (0 until testNum)
        } yield {
            val src = System.currentTimeMillis()

            val (ret, _deletedAt) = testInner(src)
            deletedAt = _deletedAt + 10
            ret must beEqualTo(true)

            logger.error(s"delete timestamp: $deletedAt")
            val deleteAllRequest = Json.arr(Json.obj("label" -> labelName, "ids" -> Json.arr(src), "timestamp" -> deletedAt))
            contentAsString(EdgeController.deleteAllInner(deleteAllRequest))

            Thread.sleep(asyncFlushInterval * 10)

            val result = getEdges(query(id = src))
            println(result)

            val resultEdges = (result \ "results").as[Seq[JsValue]]
            logger.error(Json.toJson(resultEdges).toString)
            resultEdges.isEmpty must beEqualTo(true)

            val degreeAfterDeleteAll = getDegree(result)
            degreeAfterDeleteAll must beEqualTo(0)
            true
          }

        ret.forall(identity)

        val deleteAllRequest = Json.arr(Json.obj("label" -> labelName, "ids" -> Json.arr(src), "timestamp" -> deletedAt))
        contentAsString(EdgeController.deleteAllInner(deleteAllRequest))

        Thread.sleep(asyncFlushInterval * 5)

        val result = getEdges(query(id = src))
        println(result)

        val resultEdges = (result \ "results").as[Seq[JsValue]]
        resultEdges.isEmpty must beEqualTo(true)

        val degreeAfterDeleteAll = getDegree(result)
        degreeAfterDeleteAll must beEqualTo(0)
      }
    }

    /** this test stress out test on degree
      * when contention is low but number of adjacent edges are large */
//    "large degrees" in {
//      running(FakeApplication()) {
//        val labelName = testLabelName2
//        val dir = "out"
//        val maxSize = 1000
//        val deleteSize = 90
//        val numOfConcurrentBatch = 1000
//        val src = System.currentTimeMillis()
//        val tgts = (0 until maxSize).map { ith => src + ith }
//        val deleteTgts = Random.shuffle(tgts).take(deleteSize)
//        val insertRequests = tgts.map { tgt =>
//          Seq(tgt, "insert", "e", src, tgt, labelName, "{}", dir).mkString("\t")
//        }
//        val deleteRequests = deleteTgts.take(deleteSize).map { tgt =>
//          Seq(tgt + 1000, "delete", "e", src, tgt, labelName, "{}", dir).mkString("\t")
//        }
//        val allRequests = Random.shuffle(insertRequests ++ deleteRequests)
//        //        val allRequests = insertRequests ++ deleteRequests
//        val futures = allRequests.grouped(numOfConcurrentBatch).map { requests =>
//          EdgeController.mutateAndPublish(requests.mkString("\n"), withWait = true)
//        }
//        Await.result(Future.sequence(futures), Duration(20, TimeUnit.MINUTES))
//
//        Thread.sleep(asyncFlushInterval * 10)
//
//        val expectedDegree = insertRequests.size - deleteRequests.size
//        val queryJson = query(id = src)
//        val result = getEdges(queryJson)
//        val resultSize = (result \ "size").as[Long]
//        val resultDegree = getDegree(result)
//
//        //        println(result)
//
//        val ret = resultSize == expectedDegree && resultDegree == resultSize
//        println(s"[MaxSize]: $maxSize")
//        println(s"[DeleteSize]: $deleteSize")
//        println(s"[ResultDegree]: $resultDegree")
//        println(s"[ExpectedDegree]: $expectedDegree")
//        println(s"[ResultSize]: $resultSize")
//        ret must beEqualTo(true)
//      }
//    }
//
//    "deleteAll" in {
//      running(FakeApplication()) {
//        val labelName = testLabelName2
//        val dir = "out"
//        val maxSize = 1000
//        val deleteSize = 90
//        val numOfConcurrentBatch = 100
//        val src = System.currentTimeMillis()
//        val tgts = (0 until maxSize).map { ith => src + ith }
//        val deleteTgts = Random.shuffle(tgts).take(deleteSize)
//        val insertRequests = tgts.map { tgt =>
//          Seq(tgt, "insert", "e", src, tgt, labelName, "{}", dir).mkString("\t")
//        }
//        val deleteRequests = deleteTgts.take(deleteSize).map { tgt =>
//          Seq(tgt + 1000, "delete", "e", src, tgt, labelName, "{}", dir).mkString("\t")
//        }
//        val allRequests = Random.shuffle(insertRequests ++ deleteRequests)
//        //        val allRequests = insertRequests ++ deleteRequests
//        val futures = allRequests.grouped(numOfConcurrentBatch).map { requests =>
//          EdgeController.mutateAndPublish(requests.mkString("\n"), withWait = true)
//        }
//        Await.result(Future.sequence(futures), Duration(10, TimeUnit.MINUTES))
//
//        Thread.sleep(asyncFlushInterval * 10)
//
//        val deletedAt = System.currentTimeMillis()
//        val deleteAllRequest = Json.arr(Json.obj("label" -> labelName, "ids" -> Json.arr(src), "timestamp" -> deletedAt))
//
//        contentAsString(EdgeController.deleteAllInner(deleteAllRequest))
//        Thread.sleep(asyncFlushInterval * 10)
//
//        val result = getEdges(query(id = src))
//        println(result)
//        val resultEdges = (result \ "results").as[Seq[JsValue]]
//        resultEdges.isEmpty must beEqualTo(true)
//
//        val degreeAfterDeleteAll = getDegree(result)
//        degreeAfterDeleteAll must beEqualTo(0)
//      }
//
//    }
  }
}

