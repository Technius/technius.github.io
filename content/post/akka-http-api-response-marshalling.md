+++
tags = ["scala", "akka-http"]
title = "Improving Akka HTTP REST API Response Handling with Marshallers"
date = "2017-03-20T23:31:41-07:00"
draft = true
+++

I've been trying out [akka-http](http://doc.akka.io/docs/akka-http/current/scala/http/introduction.html)
for a while now, and I found it to be great for making REST APIs. However, it is
complicated to use and can lead to a massive amount of boilerplate if its
features aren't taken advantage of completely. In this article, I'm going to
to take a look at one feature of Akka HTTP that I explored recently: marshallers.

## Motivation

To demonstrate how status codes are usually returned in Akka HTTP, let's consider
a simple example of a REST API: an online notepad. Each note should have a title
and content. The API should have basic CRUD endpoints:

* `POST /notes?title=Foo&content=bar` creates a new note titled "", storing
  "bar" as its content. The status code is `201 Created`.
* `GET /notes?id=123` retrieves the note with id 123, if it exists.
* `POST /notes?id=123&title=Foo2&content=bar2` updates the note with id 123, if
  it exists. If neither title nor content are supplied, return
  `304 Not Modified`.
* `DELETE /notes?id=123` deletes the note with id 123, if it exists.

Let's begin by modeling each note:
```scala
case class Note(id: Int, title: String, content: String)
```
Then, let's define an interface which will handle how the `Notes` are stored:
```scala
trait NoteRepository {
  def find(id: Int): Future[Option[Note]]
  def create(title: String, content: String): Future[Note]
  def update(id: Int, title: Option[String], content: Option[String]): Future[Option[Note]]
  def delete(id: Int): Future[Option[Note]]
}
```

Before we move on to writing the routes, let's write an intermediate layer that
will help translate the results into a standard, easy-to-serialize format. In
most cases, that format would be an algebraic data type hierarchy:
```scala
/* Represents the response to an API call */
sealed trait ApiResult
object ApiResult {
  /* Returns notes found */
  case class FoundNote(note: Note) extends ApiResult
  
  /* Indicates when a specified note cannot be found */
  case class NoteNotFound(id: Int) extends ApiResult
  
  /* Indicates when a note is successfully created */
  case class CreatedNote(note: Note) extends ApiResult
 
  /* Indicates when a note is successfully updated */
  case object UpdatedNote(updatedNote: Note) extends ApiResult
 
  /* Indicates when a note is sucessfully deleted. */
  case class DeletedNote(deletedNote: Note) extends ApiResult
}

trait NoteService {
  import ApiResult._

  def repo: NoteRepository

  def find(id: Int): Future[ApiResult] =
    repo.find(id).map(opt => opt.map(FoundNote(_)).getOrElse(NoteNotFound(id)))

  def create(title: String, content: String): Future[ApiResult] =
    repo.create(title, content).map(CreatedNote(_))

  def update(id: Int, title: Option[String], content: Option[String]): Future[ApiResult] =
    repo.update(id, title, content).map(UpdatedNote(_))

  def delete(id: Int): Future[ApiResult] =
    repo.delete(id).map(DeletedNote(_))
}
```

Finally, we can define our routes. The most straightforward approach would be to
call a serialization function wherever `ApiResult` is returned. For simplicity, I
used the excellent [upickle](http://www.lihaoyi.com/upickle-pprint/upickle/)
library for JSON serialization.
```scala
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import upickle.default._
trait Routes {
  def noteService: NoteService
  
  val notesRoute =
    path("notes") {
      (get & parameters('id.as[Int])) { id =>
        complete {
          noteService.find(id).map(write[ApiResult](_))
        }
      }
      (post & parameters('title, 'content)) { (title, content) =>
        complete {
          noteService.create(title, content).map(write[ApiResult](_))
        }
      }
      (delete & parameters('id.asInt]) { id =>
        complete {
          noteService.delete(id).map(write[ApiResult](_))
        }
      }
    }
}
```
Here, I used the most straightforward approach, which is to serialize each
`ApiResult` whenever it is returned. However, there are multiple problems with the
above code:

* I didn't implement status codes because it wouldn't be very easy to. For
  example, if I wanted to add status codes on the GET route, I'd have to pattern
  match on the `ApiResult` inside of the `Future` and select the correct status
  code.
* The content type isn't `application/json`.
* It's tedious and boring to extend. For example, if I wanted to add status codes
  for all routes, I'd have to pattern match in each route!
* It will be easy to introduce bugs: during a refactor, someone could
  accidentally cause a `Created` status code to be returned along with an
  `ApiResult.DeletedNote`!
* It involves far too much boilerplate for such a simple API.

# Abstracting Serialization with Marshallers

TODO: write rest

```scala
import akka.http.scaladsl.model._
import akka.http.scaladsl.marshalling._
trait ApiMarshalling {
  /* 
   * Marshaller that serializes an ApiResult into JSON and ensures that it has
   * a content type of application/json
   */
  def apiResponseTEM: ToEntityMarshaller[ApiResult] =
    Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(r => write[ApiResult](r))
}
```

We could make `apiResponseTEM` implicit, but we're not done yet: we still need
to ensure the proper status code is returned.

```scala
def getResponseCode(result: ApiResult): StatusCode = result match {
  case Foo =>
}

implicit def apiResponseTRM: ToResponseMarshaller[ApiResult] =
  PredefinedToResponseMarshallers
    .fromStatusCodeAndHeadersAndValue(apiResponseTEM)
    .compose(apiResult => (getResponseCode(apiResult), List.empty, apiResult))

```

TODO

```scala
trait Routes extends ApiMarshalling {
  def noteService: NoteService
  
  val notesRoute =
    path("notes") {
      (get & parameters('id.as[Int])) { id =>
        complete {
          // Marshaller automatically serializes ApiResult AND chooses the
          // correct status code!
          // This affects ALL values of type Future[ApiResult] returned!
          noteService.find(id)
        }
      }
    }
    // Rest omitted because it's mostly the same
}
```
