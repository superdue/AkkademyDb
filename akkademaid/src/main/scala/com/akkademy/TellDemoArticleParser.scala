package com.akkademy

import java.util.concurrent.TimeoutException

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout

import com.akkademy.messages.{GetRequest, SetRequest}

class TellDemoArticleParser(cacheActorPath: String,
                            httpClientActorPath: String,
                            articleParserActorPath: String,
                            implicit val timeout: Timeout) extends Actor {
  val cacheActor = context.actorSelection(cacheActorPath)
  val httpClientActor = context.actorSelection(httpClientActorPath)
  val articleParserActor = context.actorSelection(articleParserActorPath)

  implicit val ec = context.dispatcher

  /**
    * While this example is a bit harder to understand than the ask demo,
    * for extremely performance-critical applications, this has an advantage over ask.
    * The creation of 5 objects are saved - only one extra actor is created.
    * Functionally, it's similar.
    * It will the request to the HTTP actor without waiting for the cache response, though
    * (can be solved).
    * @return
    */

  override def receive: Receive = { case msg @ ParseArticle(uri) =>
    val extraActor = buildExtraActor(sender(), uri)
    // look in the cache and try to parse the page from the network at the same time.  Send
    // only the first result and then kill the extra actor so that no further processing is done.
    // Extra kills itself it its own receive method.
    cacheActor.tell(GetRequest(uri), extraActor)
    httpClientActor.tell("test", extraActor)
    context.system.scheduler.scheduleOnce(timeout.duration, extraActor, "timeout")
  }

  /**
    * The extra actor will collect responses from the assorted actors it interacts with.
    * The cache actor reply, the http actor reply, and the article parser actor reply are all handled.
    * Then the actor will shut itself down once the work is complete.
    * A great use case for the use of tell here (a.k.a. extra pattern) is aggregating data from multiple sources.
    */
  private def buildExtraActor(senderRef: ActorRef, uri: String): ActorRef = {
    context.actorOf(Props(new Actor{
      override def receive: Receive = {
        case body: String => // This executes when a cached result is found.
          senderRef ! body
          context.stop(self)
        // the articleParserActor will reply with an ArticleBody which is the parsing results.
        case HttpResponse(body) => articleParserActor ! ParseHtmlArticle(uri, body)
        case ArticleBody(uri, body) => // This executes when a raw parse of the html is done.
          cacheActor ! SetRequest(uri, body)
          senderRef ! body
          context.stop(self)
        case "timeout" =>
          senderRef ! Failure(new TimeoutException("timeout!"))
          context.stop(self)
        case t => println("ignoring msg: " + t.getClass)
      }
    }))
  }
}
