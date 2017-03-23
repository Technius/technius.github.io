+++
tags = ["scala", "akka-http"]
title = "Improving Akka HTTP REST API Response Handling with Marshallers"
date = "2017-03-22T23:37:03-07:00
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

* `GET /notes` retrieves all notes.
* `POST /notes?title=Foo&content=bar` creates a new note titled "", storing
  "bar" as its content. The status code is `201 Created`.
* `GET /notes/123` retrieves the note with id 123, if it exists.
* `POST /notes/123?title=Foo2&content=bar2` updates the note with id 123, if
  it exists. If neither title nor content are supplied, return
  `304 Not Modified`.
* `DELETE /notes/123` deletes the note with id 123, if it exists.

Let's begin by modeling each note:
```scala
case class Note(id: Int, title: String, content: String)
```
Then, let's define an interface which will handle how the `Notes` are stored:
```scala
trait NoteRepository {
  def list(): Future[Seq[Note]]
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
  case class UpdatedNote(updatedNote: Note) extends ApiResult
 
  /* Indicates when a note is sucessfully deleted. */
  case class DeletedNote(deletedNote: Note) extends ApiResult
}

trait NoteService {
  import ApiResult._

  def repo: NoteRepository

  def find(id: Int): Future[ApiResult] =
    repo.find(id).map(noteOp(id)(FoundNote.apply _))

  def create(title: String, content: String): Future[ApiResult] =
    repo.create(title, content).map(CreatedNote.apply _)

  def update(id: Int, title: Option[String], content: Option[String]): Future[ApiResult] =
    repo.update(id, title, content).map(noteOp(id)(UpdatedNote.apply _))

  def delete(id: Int): Future[ApiResult] =
    repo.delete(id).map(noteOp(id)(DeletedNote.apply _))

  /*
   * Helper for returning NoteNotFound if an Option is None, or passing the
   * found note to f otherwise.
   */
  private def noteOp[T](id: Int)(f: Note => ApiResult): Option[Note] => ApiResult =
    opt => opt.map(f).getOrElse(NoteNotFound(id))
}
```

Finally, we can define our routes. The most straightforward approach would be to
call a serialization function wherever `ApiResult` is returned. For simplicity, I
used the excellent [upickle](http://www.lihaoyi.com/upickle-pprint/upickle/)
library to serialize each `ApiResult` as JSON.
```scala
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import upickle.default._
trait Routes {
  def noteService: NoteService
  
  val notesRoute =
    pathPrefix("notes") {
      // -> /notes/id
      (path(IntNumber)) { id =>
        get {
          complete {
            noteService.find(id).map(write(_))
          }
        } ~
        delete {
          complete {
            noteService.delete(id).map(write(_))
          }
        } ~
        (post & parameters('title.?, 'content.?)) { (title, content) =>
          complete {
            noteService.update(id, title, content).map(write(_))
          }
        }
      } ~
      // -> /notes
      pathEndOrSingleSlash {
        get {
          complete {
            noteService.list.map(write(_))
          }
        } ~
        (post & parameters('title, 'content)) { (title, content) =>
          complete {
            noteService.create(title, content).map(write(_))
          }
        }
      }
    }
}
```
To try it out, you'll need an implementation of `NoteRepository`. I've written a
complete, naive example in this [gist](https://gist.github.com/Technius/4fddfc9e33b6c2fff13cbea5e4c67d53)
(Warning: do _not_ use this code in production!). If we run a few queries on the
server, we'll get the expected results:
```
> curl 'http://localhost:1234/notes' -w '\n\n'
{"$type":"example.ApiResult.ListNotes","notes":[]}

> curl 'http://localhost:1234/notes?title=test&content=Hi' -XPOST -w '\n\n'
{"$type":"example.ApiResult.CreatedNote","note":{"id":1,"title":"test","content":"Hi"}}

> curl 'http://localhost:1234/notes/1' -w '\n\n'
{"$type":"example.ApiResult.FoundNote","note":{"id":1,"title":"test","content":"Hi"}}

> curl 'http://localhost:1234/notes/1?title=Test&content=Hello' -XPOST -w '\n\n'
{"$type":"example.ApiResult.UpdatedNote","updatedNote":{"id":1,"title":"Test","content":"Hello"}}

> curl 'http://localhost:1234/notes?title=Post2&content=Content' -XPOST -w '\n\n'
{"$type":"example.ApiResult.CreatedNote","note":{"id":2,"title":"Post2","content":"Content"}}

> curl 'http://localhost:1234/notes' -w '\n\n'
{"$type":"example.ApiResult.ListNotes","notes":[{"id":1,"title":"Test","content":"Hello"},{"id":2,"title
":"Post2","content":"Content"}]}

> curl 'http://localhost:1234/notes/1' -XDELETE -w '\n\n'
{"$type":"example.ApiResult.DeletedNote","deletedNote":{"id":1,"title":"Test","content":"Hello"}}

> curl 'http://localhost:1234/notes' -w '\n\n'
{"$type":"example.ApiResult.ListNotes","notes":[{"id":2,"title":"Post2","content":"Content"}]}
```

If you took a close look, you'll notice multiple problems with the code:

* I didn't implement status codes because it wouldn't be very easy to. For
  example, if I wanted to add status codes on the GET route, I'd have to pattern
  match on the `ApiResult` inside of the `Future` and select the correct status
  code.
* The content type isn't `application/json`. It's just plain text (test it in your
  browser!
* It's tedious and boring to extend. For example, if I wanted to add status codes
  for all routes, I'd have to pattern match in each route!
* It will be easy to introduce bugs: during a refactor, someone could
  accidentally cause a `Created` status code to be returned along with an
  `ApiResult.DeletedNote`!
* We have to call `write` on each route. This code duplication is a clear sign
  that we can do better.
* It involves far too much boilerplate for such a simple API.

# Abstracting Serialization with Marshallers

A core part of Akka HTTP is its [marshalling API](http://doc.akka.io/docs/akka-http/current/scala/http/common/marshalling.html).
Marshallers describe how data is transformed, such as when an incoming HTTP
request is deserialized into, say, an int, or when an entity model is
serialized into an HTTP response. Using a marshaller, we can automatically
convert an `ApiResult` value into an `HttpResponse` whenever we provide it as a
response to our routes!

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
    pathPrefix("notes") {
      path(IntNumber) { id =>
        get {
          complete {
            // Marshaller automatically serializes ApiResult AND chooses the
            // correct status code!
            // This affects ALL values of type Future[ApiResult] returned!
            noteService.find(id)
          }
        }
        // ...
      }
    }
    // Rest omitted because it's mostly the same
}
```
