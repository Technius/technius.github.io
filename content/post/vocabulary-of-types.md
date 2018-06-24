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

# Algebraic Data Types

First, we'll examine a collection of types known as **algebraic data types**,
which generalize a large variety of structures used in many different
programming languages.

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

```
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

In some cases, we do not need labels at all. Consider a `Point` or `Vector` type
(e.g. in mathematics). We might write the coordinates of a point as `(5, 10)`.
This is exactly the same as a record with no labels, where we can extract the
values out of the point by referring to the position of each value. We might say
the first value (component) of `(5, 10)` is 5 and the second value of `(5, 10)`
is 10. We call this "record with no labels" a **product** (based on the idea of
Cartesian products in set theory). For example, a tuple in Python or a
fixed-length array in any language is a product. Here are some examples of
products:

```c++
// Products are usually denoted by separating all components by commas and
// surrounding the entire thing with parentheses

(5, 10)   // product with two components of the same type
(5, true) // product with two components of different types
("asdf", false, 'c', 9999) // product with four components
()        // product with no components
```

## Sums (Coproducts)

## Unit Type

## Void (Bottom)

# The Algebra of Types

## Function Types

## Parametric Polymorphism

## Isomorphisms

# Abstract Types

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
