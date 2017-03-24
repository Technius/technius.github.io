+++
tags = ["scala", "akka-http"]
title = "Improving Akka HTTP REST API Response Handling with Marshallers"
date = "2017-03-23T23:11:38-07:00"
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
most cases, that format would be an algebraic data type:
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
$ curl 'http://localhost:1234/notes' -w '\n\n'
{"$type":"example.ApiResult.ListNotes","notes":[]}

$ curl 'http://localhost:1234/notes?title=test&content=Hi' -XPOST -w '\n\n'
{"$type":"example.ApiResult.CreatedNote","note":{"id":1,"title":"test","content":"Hi"}}

$ curl 'http://localhost:1234/notes/1' -w '\n\n'
{"$type":"example.ApiResult.FoundNote","note":{"id":1,"title":"test","content":"Hi"}}

$ curl 'http://localhost:1234/notes/1?title=Test&content=Hello' -XPOST -w '\n\n'
{"$type":"example.ApiResult.UpdatedNote","updatedNote":{"id":1,"title":"Test","content":"Hello"}}

$ curl 'http://localhost:1234/notes?title=Post2&content=Content' -XPOST -w '\n\n'
{"$type":"example.ApiResult.CreatedNote","note":{"id":2,"title":"Post2","content":"Content"}}

$ curl 'http://localhost:1234/notes' -w '\n\n'
{"$type":"example.ApiResult.ListNotes","notes":[{"id":1,"title":"Test","content":"Hello"},{"id":2,"title
":"Post2","content":"Content"}]}

$ curl 'http://localhost:1234/notes/1' -XDELETE -w '\n\n'
{"$type":"example.ApiResult.DeletedNote","deletedNote":{"id":1,"title":"Test","content":"Hello"}}

$ curl 'http://localhost:1234/notes' -w '\n\n'
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
  that we can increase abstraction.
* It involves far too much boilerplate for such a simple API.

# Abstracting Serialization with Marshallers

A core part of Akka HTTP is its
[marshalling API](http://doc.akka.io/docs/akka-http/current/scala/http/common/marshalling.html).
Marshallers describe how data is transformed, such as when an incoming HTTP
request is deserialized into, say, an int, or when an entity model is serialized
into an HTTP response. They are automatically resolved through implicit scoping
and help convert data returned in `complete` directives into HTTP responses.
This is perfect for the above situation: a marshaller can be used not only
ensure the proper status code and content type is returned, but it can also be
used to handle serialization, and thus, remove boilerplate.

Since marshallers composabe, we can define our complete `ApiResult` marshaller
with smaller ones that handle separate tasks. First, let's tackle the
serialization part. If you read the documentation, you'll see that we'll need a
`ToEntityMarshaller[ApiResult]`, and that one easy way to implement it would be
to transform an existing marshaller. The `wrap` method on `Marshaller` provides
a transformation function we could use:

```scala
// Taken from the ScalaDoc
def wrap[C, D >: B](newMediaType: MediaType)(f: (C) ⇒ A)(implicit mto: ContentTypeOverrider[D]): Marshaller[C, D]
```

Based on the types, if `wrap` is called on a `Marshaller[A, B]` with a function
that converts `A` to `C`, then the result is a `Marshaller[C, B]`. In other
words, wrap transforms some other data type into the data type that the
marshaller can process. Since we want to serialize `ApiResult`, the function
given as a parameter should convert `ApiResult` into some type `B`. What should
`B` be? Well, JSON is stored as a string, so we'll want a function `ApiResult =>
String`. That's easy:

```scala
(r: ApiResult) => write[ApiResult](r)
```

Next, we need a content type, which will obviously be `application/json`.
Actually, it's nice that `wrap` requires a `MediaType` parameter, since we won't
have to specify it elsewhere.

Now, we just need a `ToEntityMarshaller[String]`, which can be grabbed from Akka
HTTP's predefined marshallers. Putting it altogether, we can now write a trait
with our marshaller:

```scala
import akka.http.scaladsl.model._
import akka.http.scaladsl.marshalling._
/**
  * Mix in to provide marshalling capabilities for `ApiResult`.
  */
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
to ensure the proper status code is returned. We can just pattern match on the
specific type of `ApiResult`. In addition, since the compiler performs an
exhaustivity check, we can be sure that all `ApiResult`s are handled.

```scala
import ApiResult._
def getResponseCode(result: ApiResult): StatusCode = result match {
  case _: FoundNote => StatusCodes.OK
  case _: NoteNotFound => StatusCodes.NotFound
  case _: CreatedNote => StatusCodes.Created
  // To return a 304 Not Modified, UpdatedNote and the associated logic needs to
  // be changed so that it can capture the possibility that no changes were made.
  // Try it as an exercise!
  case _: UpdatedNote => StatusCodes.OK
  case _: DeletedNote => StatusCodes.OK
}
```

TODO

```
implicit def apiResponseTRM: ToResponseMarshaller[ApiResult] =
  PredefinedToResponseMarshallers
    .fromStatusCodeAndHeadersAndValue(apiResponseTEM)
    .compose(apiResult => (getResponseCode(apiResult), List.empty, apiResult))
```

Finally, let's mix in the `ApiMarshalling` trait.

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
            noteService.find(id)
          }
        }
        // ...
      }
    }
    // Rest omitted because it's mostly the same
}
```

If you remembered, `find` returns a `Future[ApiResult]`. Akka HTTP has implicit
functions to derive `ToResponseMarshaller[Future[A]]` from a
`ToResponseMarshaller[A]`, so the use of `Marshaller`s gives us another
advantage, especially since all of the `NoteService` functions return `Future`s.
