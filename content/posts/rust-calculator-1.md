+++
date = "2017-01-13"
title = "Creating a Calculator in Rust, Part 1"
+++

As an exercise to learn more about Rust, I decided to write a calculator
program. It's able to evaluate arbitrary numerical expressions (i.e.
`2 * 3^5 / 3`), as well as simple algebraic expressions (i.e. `3x + 5x`). These
articles will document some of the hurdles that I ran into, as well as the
solutions that I came up with. Well, let's not linger any longer and jump right
in!

# Defining the AST

After creating the project files with `cargo`, let's start by defining the
[abstract syntax tree](https://en.wikipedia.org/wiki/Abstract_syntax_tree) that 
represents each numerical expression. An easy way to do that is to come up with
some sample expressions that we want to evaluate, such as

* `1 + 1 - 2`
* `3 * 2`
* `5 / 3`
* `(1 + 1) * 2`
* `3x + 5x`

Based on the first example, it seems like we'll need a term to represent a
constant, as well as a term to represent a sum. Subtraction (i.e `2 - 1`) is
special case of a sum with a negative term, so we don't have to add a term for
it. Next, we'll need terms for multiplication and division. We can't just use
one term for both like we can for addition and subtraction, since it's possible
to multiply by zero but not divide by zero. Let's go ahead and sketch out the
initial enum:

```rust
enum Expr {
    Const(f64),
    Sum(f64, f64),
    Product(f64, f64),
    Quotient(f64, f64)
}
```

There's a problem with this representation, though; we can't express chained
operations like `1 + 1 * 2`. We'll have to modify the enum to hold Expr instead.
The simplest approach may be to replace the `f64`s in `Sum`, `Product`, and
`Quotient` with `Expr`:
```rust
enum Expr {
    Const(f64),
    Sum(Expr, Expr),
    Product(Expr, Expr),
    Quotient(Expr, Expr)
}
```

Unfortunately, this fails to compile:
```
error[E0072]: recursive type `Expr` has infinite size
```

A `Sum` could contain infinitely many `Sum`s, for example, so it would be
impossible for Rust to determine how large an Expr would be. Instead, we'll have
to use references inside of each recursive term. We could write `&Expr`, but then
we'd have to manage a lot of lifetimes. Instead, let's use `Box`:

```rust
enum Expr {
    Const(f64),
    Sum(Box<Expr>, Box<Expr>),
    Product(Box<Expr>, Box<Expr>),
    Quotient(Box<Expr>, Box<Expr>)
}
```

Great, it compiles! Let's try it out by writing out `1 + 1` and `1 + 1 * 2`:
```rust
let one_plus_one =
    Expr::Sum(
        Box::new(Expr::Const(1.0)),
	Box::new(Expr::Const(1.0))
    );

let one_plus_one_times_two = 
    Expr::Sum(
        Box::new(Expr::Const(1.0)),
	Box::new(
	    Expr::Product(
	        Box::new(Expr::Const(1.0)),
	        Box::new(Expr::Const(2.0))
	    )
	)
    );
```
Unfortunately, it requires a lot of boilerplate, but it works. After adding `#[derive(Debug)]` above
the `Expr` enum, we can print out an `Expr` e.g.
`println!("{:?}", one_plus_one)`.

# Implementing a DSL

It'd be nice if we could write out calculations directly in our code. That's
sounds like a good case for writing a macro, but let's try something simpler
instead: making a DSL. The greatest inconvenience is probably the fact that we
have to box each term in a sum or product. Let's write a few helper functions to
automatically do the boxing for us. Implementation should be very
straightforward:
```rust
impl Expr { // In this section, we'll just focus on sum and product
    fn sum(lh: Expr, rh: Expr) -> Expr {
        Expr::Sum(Box::new(lh), Box::new(rh))
    }

    fn product(lh: Expr, rh: Expr) -> Expr {
        Expr::Product(Box::new(lh), Box::new(rh))
    }
}
```
Now, instead of writing `Expr::Sum(Box::new(...), Box::new(...))`, we could just
write `Expr::product(Expr::Const(1), Expr::Const(2))`. This is already much
better, but let's take it a step further: let's aim for something like
`Expr::sum(1, Expr::product(1, 2))`. We don't want to just limit our parameters
to `Expr`, but we also want to include `f64`. We can achieve that by using a
type parameter with a trait bound on it:
```rust
fn sum<L, R>(lh: L, rh: R) -> Expr where L: Into<Expr>, R: Into<Expr> {
    Expr::Sum(Box::new(lh.into()), Box::new(rh.into()))
}

fn product<L, R>(lh: L, rh: R) -> Expr where L: Into<Expr>, R: Into<Expr> {
    Expr::Product(Box::new(lh.into()), Box::new(rh.into()))
}
```
The [`Into` trait](https://doc.rust-lang.org/std/convert/trait.Into.html)
lets us use a type `T` as a parameter given that we implement an `into` function
which converts from a `T` to the target type (which, in our case, is `Expr`). In
the case of the above function, we'll use `L` and `R` instead of a single `T`,
since the left and right hand parameters could have different types, like in
`Expr::sum(1, Expr::sum(1, 2))`.

Speaking of which, we can't write an expression using `f64` directly without
implementing `Into<Expr>` for `f64`. However, it seems more natural to
implement a `From<f64>` for `Expr` ([which provides an `Into` instance for free]
(https://doc.rust-lang.org/std/convert/trait.From.html#generic-impls)) instead,
since we want to convert _from_ an `f64` to an `Expr`.
```rust
impl From<f64> for Expr {
    fn from(x: f64) -> Expr {
        Expr::Const(x)
    }
}
```

We can now write out `sum` and `product` with a mix of `f64` and `Expr`. Let's
rewrite `one_plus_one` and `one_plus_one_times_two` and add an even more
complicated expression:
```rust
let one_plus_one = Expr::sum(1.0, Expr::Const(1.0));
let one_plus_one_times_two = Expr::sum(1.0, Expr::product(1.0, 2.0));

let complicated_expr =
    Expr::sum(Expr::product(1.0, 2.0), Expr::product(3.0, 4.0));
```

# Bonus: Natural-looking Expressions
Going even further, we could make it possible to use the `+`, `-`, `*`, and `/`
operators in our code. All we have to do is implement the `Add`, `Sub`, `Mul`,
and `Div` traits, respectively, by specifying the resulting type (which, in our 
case, is `Expr`) and implementing a function corresponding to the operation. All
we have to do is call the functions that we've already written. The impl for
`Add` is shown below; the others are similar.
```rust
use std::ops::{Add, Sub, Mul, Div};
impl Add for Expr {
    type Output = Expr;
    fn add(self, other: Expr) -> Expr {
        Expr::sum(self, other)
    }
}
```
Now, we can simplify `complicated_expr` to
```rust
Expr::product(1.0, 2.0) + Expr::product(3.0, 4.0)
```
We can't just write `1.0 * 2.0 + 3.0 * 4.0`, since Rust will simply evaluate
that to a `f64`. In any case, additional simplication won't really help
readability, so let's stop here (we could always try to write a macro, though.
Maybe next time).

# What's Next
I've shown how to compose an AST for a calculator in Rust. I didn't implement
the DSL for `Quotient`, but it should be similar to implementing `Add` and
`Product`. Of course, simply defining our data structure isn't very
interesting---we want to be able to do something with it! In the next post in
the series, I'll explore how to evaluate our expressions and turn the calculator
into an _actual_ calculator.
