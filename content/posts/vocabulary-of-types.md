+++
date = "2018-06-23"
title = "The Vocabulary of Types"
categories = ["misc"]
draft = true
+++

One of the most fundamental processes in programming is to select or construct
the most appropriate data types to model problems. For instance, you might want
to know what the _best_ way to model a form on a webpage is. Or you might want
to know whether two different models of a database represent the same
information.

While these types of problems involving _structure_ crop up in almost all
situations, we don't put enough of a focus on the fundamentals behind structures
of data. In this article, I'd like to explore data types common to all
programming languages, as well as reasons why they're interesting and useful to
know about.

# Some Basic Notation

Before we get started, I'll introduce some notation that's commonly used in type
theory literature.

We often need to talk about a value and its type, so we write `t : T` to mean
"`t` has type `T`." The phrase "`t` inhabits `T`" means the same thing.

We will be describing the type signature of a function quite often, so we'll use

```scala
f : Arg1 -> Arg2 -> ... -> ArgN -> Result
```

to denote a function `f` as taking `n` arguments of types `Arg1, Arg2, ...,
ArgN` that returns a value of type `Result`. For example, we might write

```scala
add : Int -> Int -> Int
generateReport : Int -> Bool -> String -> String
```

to show that `add` is a function that takes two `Int`s and returns an `Int`, and
that `generateReport` is a function that takes an `Int`, a `Bool`, and a
`String` and returns a `String`.

When the progamming language is not indicated, we will write functions using the
following psuedocode format:

```scala
// A comment

functionName : Arg1 -> Arg2 -> ... -> ArgN      // type signature of function
functionName(parameters...) = {                 // function implementation
  if (foo) {                   // if-statements evaluate to last value inside
    bar
  } else {
    baz
  }
  // Last expression in a function is returned (e.g. the `if` in this case)
}
```

# Algebraic Data Types

First, we'll examine a collection of types known as **algebraic data types**,
which generalize a large variety of types, such as structs, classes, tuples, and
enums, used in many different programming languages.

## Records

Most programming languages have some sort of structure that bundles different
values together. In C, we have the struct:

```c
struct StoreItem {
  float price;
  bool inStock;
};

StoreItem myItem;
myItem.price = 13.99;
myItem.inStock = true;
```

In JavaScript, we have the object:
```javascript
var myItem = {
  price: 13.99,
  inStock: true
};
```

In Java (and C++ as well), we have the class:
```java
class StoreItem {
  float price;
  boolean inStock;
}

StoreItem myItem = new StoreItem();
myItem.price = 13.99;
myItem.inStock = true;
```

In each of the examples above, we can extract out `price` and `inStock`:
```plain
myItem.price
myItem.inStock
```

This gives us the notion of a **record**: a type that contains zero or more
values called **fields** that can be extracted out by referring to the
**labels** of the fields. The labels must be unique, but they can have different
types. For example, the `StoreItem` above is a record with two fields labeled
`price` and `inStock`. Every value labelled `price` is of type `Float` and every
value labelled `inStock` is of type `Bool`. More generally, a C struct is a
record, a JavaScript object is a record, and a Java/C++ class is a record.

Here are some more examples of records, in C:
```c
struct Foo {
  int bar; // every field has a label
  int baz;
};

struct PersonInfo {
  char name[256];
  int age; // type of each field can be different
};

struct NoLabels { // can have no fields
};
```

and here are non-examples:

```c
struct NotARecord {
  int bar;
  int bar; // labels must be unique!
}

struct NotARecord2 {
  int;   // no label!
  char*; // no label!
}
```

Note that fields do not necessarily need statically-known types; e.g. objects in
JavaScript, or similar constructs in other dynamically-typed languages, are
records (as an aside, according to Robert Harper, all values in
dynamically-typed languages happen to have the same type). Also, the definition
of a record doesn't say anything about _constructing_ a record--only
_extracting_ values out of a record.

If `StoreItem` is a record in each of the languages, are the `StoreItem`s in
C, JavaScript, and Java the same thing? It looks obvious, but how can we be
certain?

We might say that two records are equal if they contain the same labels and each
label has the same type in both records. However, this definition is too rigid.
For example, consider `StoreItem` and the `StoreItem2` shown below:

```c
struct StoreItem2 {
  float Price;
  bool InStock;
}
```

Although `StoreItem` and `StoremItem2` are conceptually the same thing, they are
not equal by our definition because they have different labels (`StoreItem`'s
labels are camel-cased while `StoreItem2`'s are uppercased). Let's loosen our
definition by saying that two records are "equal" if they are _structurally_
equal--that is, their fields have the same types. This would fit with out
example: `StoreItem` and `StoreItem2` are structurally equal because they
contain exactly one `float` and one `bool`. But if we're talking about equality
of records, what does it mean for two types to be the "same"? The equality of
types will reoccur throughout this article, and we will explore it in-depth
later.

## Products

In some cases, we care only about the _contents_ and not the labels. Consider a
`Point` or `Vector` type (e.g. in mathematics). We might write the coordinates
of a point as `(5, 10)`. This is exactly the same as a record with no labels,
where we can extract the values out of the point by referring to the position of
each value. We might say the first value (component) of `(5, 10)` is 5 and the
second value of `(5, 10)` is 10. We call this kind of record with no labels a
**product type** (based on the idea of Cartesian products in set theory). For
example, a tuple in Python or a fixed-length array in any language is a product.
Here are some examples of products:

```c++
// Products are usually denoted by separating all components by commas and
// surrounding the entire thing with parentheses

(5, 10)   // product with two components of the same type
(5, true) // product with two components of different types
("asdf", false, 'c', 9999) // product with four components
()        // product with no components
```

To be more precise, a product is characterized entirely by its ability to
"extract" out its values through functions (or built-in operations) called
**projections**. That is, if we have some type `Foo` with the following projection
functions

```scala
proj1 : Foo -> Int
proj2 : Foo -> String
```

then `Foo` is a product type `(Int, String)` (or `(String, Int)`, which is
structurally equal).

If we drop all of the labels from a record, we'll obtain a product with the same
values as the record. This is due to the fact that records have _more_
information than products (in the form of labels), which make products
relatively "purer" concepts. This means that it's easy to convert a group of
arbitrary records into a group of values of the same product type, but it may be
impossible to recover the original record types.

## Sums (Coproducts)

Both records and products provide a way to _extract_ values from a single type.
Now, we'll look at **sum types** (also known as **variants**), which provide
ways to _inject_ values into a the sum type. Since most mainstream languages do
not directly support sum types, we'll start with the precise definition this
time and then give examples.

For any sum type `T + U`, there exist two functions

```scala
inl : T -> T + U           // "Left" injection, since T is on the left
inr : U -> T + U           // "Right" injection
```

that are defined on all of their inputs (e.g. they will not crash or cause
undefined behavior for any input). What these functions means is that we can
construct a `T + U` with either a `T` or a `U`. This doesn't seem very practical
at first, so let's look at an example.

Suppose we want to write a `safeDivide` function that takes two integers `a` and
`b` and will return an error message if `b` is zero (instead of crashing) or
return `a / b` otherwise. What should the type of our `safeDivide` function be?
If our language has exceptions, then we can just throw an exception, but that
doesn't guarantee that the program won't crash (e.g. if the exception isn't
handled). A truly safe way would be to return a sum type `String + Int`, which
would _force_ any users of `safeDivide` to handle the error:

```scala
safeDivide : Int -> Int -> String + Int
safeDivide(a, b) = {
  if (b == 0) {
    inl("Cannot divide by zero")    // String is on the left
  } else {
    inr(a / b)                      // Int is on the right
  }
}

safeDivide(5, 0) : String + Int
safeDivide(5, 10) : String + Int
```

For the sum type to be useful, we need to be able to extract the values out.
Although the definition of a sum type does not necessarily require extraction
(it does, however, require injection), many programming languages that support
sum types provide "pattern matching" features that allow us to consider every
possible value injected into (every possible constructor used in) a sum type.
For example, Scala supports sum types through `case class` and has the `match`
command:

```scala
sealed class StringOrInt   // A sum type `String + Int`
final case class Left(value: String) extends StringOrInt      // inl
final case class Right(value: Int) extends StringOrInt        // inr

val divideResult =
  safeDivide(5, 10) match {
    case Left(error) => println(error)         // Prints an error
    case Right(x) => println(x)                // or prints out the value of `a / b`
  }
```

Haskell supports sum types directly through `data` declarations and uses `case`
expressions to consider each possible value:

```haskell
data StringOrInt = Left String | Right Int

divideResult =
  case safeDivide 5 10 of
    Left error -> putStrLn error
    Right x -> putStrLn (show x)
```

And Rust and Swift support sum types through `enum`s, and they have `match` and
`switch` expressions, respectively (not shown).

In these languages, because we can _only_ obtain a value of `String + Int`
through the sum type constructors, and we consider _every possible constructor_,
we can safely perform our divide and catch any errors in the process.

## Isomorphisms

We can easily extend a sum type beyond two "injections" by making one of them
another sum type. For example, to represent different categories of media, we
might use a sum type such as `Movie + (VideoGame + (Music + (Painting + Book)))`.

## Unit Type

## Void (Bottom)

# On the Algebra of Types

## Structural Equivalence

## Currying

## Sums of Products and Products of Sums

## More Examples

# More General Types

## Parametric Polymorphism

## Higher-Order Types

## Rank-N Types

# Going Further

## Subtyping

## Dependent Types

## Category Theory

## Further Reading

* _Seven Sketches in Compositionality: An Invitation to Applied Category Theory_
  by Brandan Fong and David I. Spivak
* _Types and Programming Languages_ by Benjamin C. Pierce
* _Type-Driven Development with Idris_ by Edwin Brady
* _Software Foundations_ by Benjamic C. Pierce et al.
