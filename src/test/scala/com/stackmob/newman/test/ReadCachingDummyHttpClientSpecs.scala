/**
 * Copyright 2012-2013 StackMob
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stackmob.newman.test

import caching.DummyHttpResponseCacher
import scalacheck._
import org.specs2.{ScalaCheck, Specification}
import com.stackmob.newman.caching._
import com.stackmob.newman.response.HttpResponse
import org.scalacheck._
import Prop._
import java.net.URL
import java.util.concurrent.TimeUnit
import com.stackmob.newman._
import com.stackmob.newman.request.HttpRequestWithoutBody
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class ReadCachingDummyHttpClientSpecs extends Specification with ScalaCheck { def is =
  "ReadCachingDummyHttpClientSpecs".title                                                                               ^ end ^
  "CachingDummyHttpClient is an HttpClient that caches responses for some defined TTL"                                  ^ end ^
  "get should read from the cache if there is an entry already"                                                         ! getReadsOnlyFromCache ^ end ^
  "get should read through to the cache"                                                                                ! getReadsThroughToCache ^ end ^
  "head should read from the cache if there is a cache entry already"                                                   ! headReadsOnlyFromCache ^ end ^
  "head should read through to the cache"                                                                               ! headReadsThroughToCache ^ end ^
  "POST, PUT, DELETE should not touch the cache"                                                                        ! postPutDeleteIgnoreCache ^ end ^
  end

  private case class CacheInteraction(numGets: Int, numSets: Int, numExists: Int)
  private def verifyCacheInteraction(cache: DummyHttpResponseCacher, interaction: CacheInteraction) = {
    val getCalls = cache.getCalls.size must beEqualTo(interaction.numGets)
    val setCalls = cache.setCalls.size must beEqualTo(interaction.numSets)
    val existsCalls = cache.existsCalls.size must beEqualTo(interaction.numExists)
    getCalls and setCalls and existsCalls
  }

  private case class ClientInteraction(numGets: Int, numPosts: Int, numPuts: Int, numDeletes: Int, numHeads: Int)
  private def verifyClientInteraction(client: DummyHttpClient, interaction: ClientInteraction) = {
    val getReqs = client.getRequests.size must beEqualTo(interaction.numGets)
    val postReqs = client.postRequests.size must beEqualTo(interaction.numPosts)
    val putReqs = client.putRequests.size must beEqualTo(interaction.numPuts)
    val deleteReqs = client.deleteRequests.size must beEqualTo(interaction.numDeletes)
    val headReqs = client.headRequests.size must beEqualTo(interaction.numHeads)

    getReqs and postReqs and putReqs and deleteReqs and headReqs
  }

  private def genDummyHttpResponseCache(genOnGet: Gen[Option[Future[HttpResponse]]],
                                        genOnSet: Gen[Future[HttpResponse]],
                                        genOnExists: Gen[Boolean],
                                        genOnRemove: Gen[Option[Future[HttpResponse]]]): Gen[DummyHttpResponseCacher] = {
    for {
      onGet <- genOnGet
      onSet <- genOnSet
      onExists <- genOnExists
      onRemove <- genOnRemove
    } yield {
      new DummyHttpResponseCacher(onGet = onGet, onSet = onSet, onExists = onExists, onRemove = onRemove)
    }
  }

  private def genDummyHttpClient: Gen[DummyHttpClient] = for {
    resp <- genHttpResponse
  } yield {
    new DummyHttpClient(Future.successful(resp))
  }

  private def verifyReadsOnlyFromCache[T <: HttpRequestWithoutBody](fn: (ReadCachingHttpClient, URL, Headers) => T) = {
    val genOnGet = genSomeOption(genSuccessFuture(genHttpResponse))
    val genOnSet = genSuccessFuture(genHttpResponse)
    val genOnExists = Gen.value(true)
    val genOnRemove = genSomeOption(genSuccessFuture(genHttpResponse))
    forAll(genURL,
      genHeaders,
      genDummyHttpClient,
      genDummyHttpResponseCache(genOnGet, genOnSet, genOnExists, genOnRemove)) { (url, headers, dummyClient, dummyCache) =>

      val client = new ReadCachingHttpClient(dummyClient, dummyCache)

      val req = fn(client, url, headers)
      val resp = req.apply.block()
      //the response should match what's in the cache, not what's in the underlying client
      val respMatches = dummyCache.onGet must beSome.like {
        case r => {
          r.block() must beEqualTo(resp)
        }
      }

      //there should be 1 cache get, a hit, and there should be no client interaction at all since it hit the cache
      respMatches and verifyCacheInteraction(dummyCache, CacheInteraction(1, 0, 0)) and verifyClientInteraction(dummyClient, ClientInteraction(0, 0, 0, 0, 0))
    }
  }

  private def verifyReadsThroughToCache[T <: HttpRequestWithoutBody](createRequest: (ReadCachingHttpClient, URL, Headers) => T,
                                                                     createClientInteraction: Int => ClientInteraction) = {
    val genOnGet = genNoneOption[Future[HttpResponse]]
    val genOnSet = genSuccessFuture(genHttpResponse)
    val genOnExists = Gen.value(false)
    val genOnRemove = genSomeOption(genSuccessFuture(genHttpResponse))
    forAll(genURL,
      genHeaders,
      genDummyHttpClient,
      genDummyHttpResponseCache(genOnGet, genOnSet, genOnExists, genOnRemove)) { (url, headers, dummyClient, dummyCache) =>

      val client = new ReadCachingHttpClient(dummyClient, dummyCache)
      val req = createRequest(client, url, headers)
      val resp = req.apply.block()
      val respVerified = resp must beEqualTo(dummyClient.responseToReturn.block())
      //there should be a single client call after the cache miss
      val respClientVerified = verifyClientInteraction(dummyClient, createClientInteraction(1))
      //there should be a get call, a miss, then a set call to perform the write back to the cache,
      //after we've talked to the client
      val respCacheVerified = verifyCacheInteraction(dummyCache, CacheInteraction(1, 1, 0))

      respVerified and respClientVerified and respCacheVerified
    }
  }

  private def getReadsOnlyFromCache = verifyReadsOnlyFromCache { (client, url, headers) =>
    client.get(url, headers)
  }
  private def getReadsThroughToCache = verifyReadsThroughToCache( { (client, url, headers) =>
    client.get(url, headers)
  }, { numGets =>
    ClientInteraction(numGets, 0, 0, 0, 0)
  })

  private def headReadsOnlyFromCache = verifyReadsOnlyFromCache { (client, url, headers) =>
    client.head(url, headers)
  }
  private def headReadsThroughToCache = verifyReadsThroughToCache({ (client, url, headers) =>
    client.head(url, headers)
  }, { numHeads =>
    ClientInteraction(0, 0, 0, 0, numHeads)
  })

  private def postPutDeleteIgnoreCache = {
    val genOnGet = genSomeOption(genSuccessFuture(genHttpResponse))
    val genOnSet = genSuccessFuture(genHttpResponse)
    val genOnExists = Gen.value(true)
    val genOnRemove = genSomeOption(genSuccessFuture(genHttpResponse))

    forAll(genURL,
      genHeaders,
      genRawBody,
      genDummyHttpClient,
      genDummyHttpResponseCache(genOnGet, genOnSet, genOnExists, genOnRemove)) { (url, headers, body, dummyClient, dummyCache) =>
      val client = new ReadCachingHttpClient(dummyClient, dummyCache)

      val postRes = client.post(url, headers, body).block() must beEqualTo(dummyClient.responseToReturn.block())
      val putRes = client.put(url, headers, body).block() must beEqualTo(dummyClient.responseToReturn.block())
      val deleteRes = client.delete(url, headers).block() must beEqualTo(dummyClient.responseToReturn.block())

      postRes and
      putRes and
      deleteRes and
      verifyCacheInteraction(dummyCache, CacheInteraction(0, 0, 0)) and
      verifyClientInteraction(dummyClient, ClientInteraction(0, 1, 1, 1, 0))
    }
  }
}
