---
description: An example of applying ILP to solve an optimization problem.
date: 2024-07-09

search:
  exclude: true
hide: [navigation, toc]
---

# Optimizing FFXIV Trust EXP with Integer Linear Programming

I've been a long time player of _Final Fantasy XIV_, and in the past weeks I've
been playing its recently released expansion _Dawntrail_.
As is typical for expansions to massively-multiplayer online role-playing games,
_Dawntrail_ raised the level cap--from level 90 to level 100.
This also includes a corresponding increase to the level cap of the Trust
system, a game mode where the player can form a party with three AI-controlled
non-playable characters (NPCs) and take them into a dungeon.
Since each dungeon takes approximately 30 minutes to clear and there are 8 NPCs
to level up in total, it would take quite a lot of time to get all NPCs to
level 100.

This begs the question: what would be a strategy for minimizing the number of
runs (i.e., time) required to get all NPCs to level 100?
To get an answer, I ended up writing a calculator that can compute such a
strategy, and I will be describing how it works in this article--no background
knowledge on FFXIV required.
You can find the source code [here][calculator-sources].

<!-- more -->

!!! warning
    While I've tried to keep this article spoiler-free, the article will refer
    to some NPCs that are unlocked in _Shadowbringers_, _Endwalker_, and
    _Dawntrail_.
    If you are playing FFXIV but haven't started the story for _Dawntrail_,
    proceed at your own risk.

## A Problem of Trust

Before we think about a strategy, let's try to understand how Trusts work in
detail.

In the Trust system, the player selects a _party_ consisting of exactly four
characters, where one character takes on the role of a "tank", one character
takes on the role of a "healer", and two characters take on the role of "DPS".
The player chooses which role they want to play themself, and then they choose
AI-controlled NPCs to fill the remaining roles.
Each NPC has a different set of roles; for example, one of them can only take
on the tank role, while another character can take on two different roles.

!!! example
    One possible Trust party is one where the player decides to play as a healer
    role, and then they choose the characters Thancred, Alphinaud, and Alisaie
    for the tank and two DPS roles, respectively.

    Thancred and Alisaie each only have one role.
    However, Alphinaud may be chosen for either a healer or DPS role.

Once the player has formed a party, they can then play through a _dungeon_.
Each playthrough, which I will refer to as a "dungeon run", takes approximately
30 minutes to complete.

### Levels

Every character has two numerical quantities called _level_ and _experience_
(typically shortened as "EXP").
The level is a reflection of the progress or strength of the character, and it
is a general goal of the game to increase the level to its maximum value
possible (the "level cap", which is 100 for _Dawntrail_).
In order to increase the level, the player must accumulate _experience points_
by accomplishing tasks, such as completing a Trust dungeon.

Each dungeon has a minimum level requirement, such that a dungeon can only be
run if all party members have a level exceeding that of the minimum.
There are five _Dawntrail_ dungeons available with the Trust system, unlocked at
levels 91, 93, 95, 97, and 99, respectively.
For example, the level 95 dungeon can only be run by a party where all members
are at least level 95.

Once a dungeon run is fully complete, a fixed amount of EXP will be awarded to
all Trust NPCs that participated in that run.
Higher level dungeons award more EXP.

For the dungeons from levels 91 to 100, the Trust system provides 8 NPCs that
may be used in parties.
The amount of EXP required to level an NPC up increases as the NPC's level
increases;
however, for each level, the amount of EXP required to level up is the same for
all characters.

### Trust EXP

The problem, which I will call the "Trust EXP Problem", consists of coming up
with a list of parties (duplicates allowed) that can be be used in Trust dungeon
runs, such that the NPCs can be levelled up from level 91 to level 100.
Specifically, we would like to minimize the total number of dungeon runs that
must be performed so-as to save time.

I'll use the term "strategy" to refer to a solution to the problem.
For example, a strategy for leveling all eight NPCs from level 91 to 92 could be
to run the dungeon three times with the first three NPCs, run the dungeon three
times with the next three NPCs, and then run the dungeon three more times with
any party that contains the last two NPCs.

!!! note
    EXP considerations are somewhat secondary here as they are a somewhat
    continuous measurement of the progress towards leveling up.
    One issue is that EXP is awarded in discrete, not continuous chunks.
    For example, the level 91 dungeon awards about 5.8 million EXP, but leveling up
    from 91 to 92 requires 13 million EXP.
    It will therefore take 3 dungeon runs to level up from 91 to 92.
    However, those 3 runs will award more EXP than is required to level up.

    We will focus on the number of runs initially.
    Later, we will see that EXP needs to be factored in to construct a better
    strategy.

## Formulating the Trust EXP Problem as an ILP problem

A solution to this problem will be lower-bounded by $M$, while the objective
function will be minimized.
If we can find a linear relationship between (1) the total number of runs that a
given NPC participates in and (2) the total number of parties that need to be
formed, then integer linear programming (ILP) might be a good fit for this
problem.

### What is ILP?

For those that are unfamiliar with ILP, I'll briefly explain the basics of ILP.
Disclaimer: my experience with ILP is limited to whatever I can remember of the
algorithmic graph theory course I took as a sleep-deprived and overworked graduate
student.

ILP is a useful tool for solving optimization problems involving linear
quantities.
The general idea is to mathematically model an optimization problem as an ILP
problem, and then use an off-the-shelf ILP solver to that can efficiently search
for a solution to the problem.
Once we obtain a solution to the ILP problem, we can transform it into a
solution for the original optimization problem.

An ILP problem can be formally defined as follows:

> Given a matrix $\mathbf{A}$ and constant vectors $\vec{c}$ and $\vec{b}$, find
> $\vec{x}$ minimizing $\vec{c} \cdot \vec{x}$, subject to the constraint
>
> $$
> \mathbf{A}\vec{x} \geq \vec{b}
> $$

In plainer language, formulating an optimization problem as an ILP problem
involves three parts:

1. Defining the numerical quantities involved in the problem (the $\vec{c}$,
   $\vec{b}$, and $\vec{x}$ part).
   Importantly, the $\vec{x}$ part is the "solution" to the ILP problem.
2. Constructing _constraints_ that define when a potential solution is valid
   (the $\mathbf{A}\vec{x} \geq \vec{b}$ part).
   In ILP terminology, a solution that satisfies all of the constraints is
   called a _feasible_ solution.
3. Defining an _objective function_ that can be used to measure how good a
   solution is (the $\vec{c} \cdot \vec{x}$ part).

!!! tip
    You may have seen ILP problems where the problem is to maximize the
    objective function subject to upper-bound constraints.
    Any ILP maximization problem can be transformed into a similar minimization
    problem, and vice versa.
    This is known as _duality_, and there are several nice theorems that relate
    the solutions of an ILP problem and those of its dual.

### You are not without allies

Since teams are central to the Trust EXP Problem, it's useful to precisely
define what we mean by "team".

* A _team_ $t$ is a triple of NPCs that are used to form a party.
  There are three categories of teams, depending on which role the player
  chooses for themself:
  1. A team consisting of one healer and two DPS.
  2. A team consisting of one tank and two DPS.
  3. A team consisting of one tank, one healer, and one

  For example, $t = \text{(Thancred, Alphinaud, Alisaie)}$ is a team where the members take
  on the tank, healer, and DPS role, respectively.

* Let $T$ mean the set of all possible teams.
  This can be computed by enumerating the teams in each category.
  Notation-wise, let's assume the teams are numbered from 1 to $|T|$.
* Define the _count_ $C_t$ of a team $t$ as the number of times $t$ is taken on
  a dungeon run.
  We'll abuse the notation here to allow a number as a subscript, e.g. $C_1$
  refers to the team numbered as 1.
* A _strategy_ is a vector
  $\vec{x} = \left\langle{C_1, \dots, C_{|T|}}\right\rangle$
  that serves as a solution to the ILP problem.
  Note that I'll use the term "strategy" to refer to both an ILP solution and a
  solution to the Trust EXP Problem, as it is trivial to transform between them.

For example, the strategy where $C_{(\text{Thancred, Alphinaud, Alisaie})} = 3$
and $C_t = 0$ for all other teams $t$ is one where the team (Thancred,
Alphinaud, Alisaie) should be used three times (which will level them up from
91 to 92), but no other dungeon runs should be performed.

### Working strats, please

In order to level up all of the NPCs, we have to actually pick a minimum number
of teams in order to make progress.
As an example, consider the strategy $C_t = 0$ for all teams; this represents a
strategy where no dungeon runs are undertaken.
We'd _never_ want to consider a strategy like this, since it won't actually
solve the problem.

To avoid infeasible solutions, we need to define a set of _constraints_ that a
"valid" strategy must satisfy.
To keep things simple, let's assume that all of the NPCs start at the same
level, with 0 EXP accumulated towards the next level.
We know that each NPC $c$ must be involved in at least a minimum number of
dungeon runs $M$.
For example, to go level 91 to 92, each NPC needs to be involved in $M
= 3$ dungeon runs.

We can calculate the total number of dungeon runs an NPC $c$ is involved in by
counting the total number of times the teams involving $c$ are used.
Specifically, given a team $t$, we want some quantity that is 0 when $c$ is
_not_ on $t$ and $C_t$ when $c$ is on $t$.
This can be expressed using the sum:

$$
\sum_{t \in T} I[c \in t]C_t
$$

where $I$ is the indicator function.
The indicator function is defined as 0 when the given predicate is true or 1
otherwise.

We can relate this count back to $M$ by imposing the following constraint on
each NPC $c$:

$$
\sum_{t \in T} I[c \in t]C_t \geq M
$$

We also need to restrict each $C_t$ so that it can't be negative, as it
doesn't make any sense to select a team a negative number of times.

$$
C_t \geq 0
$$

To relate this back to the matrix $\mathbf{A}$ and the vector $\vec{b}$, the
rows of $\mathbf{A}$ are the left-hand side of the constraints, while the
components of $\vec{b}$ come from the right-hand side of the constraints.

### Sizing up a strategy

Ideally, we would want the strategy to involve as few dungeon runs as possible.
Since each dungeon run corresponds to one team, this means we want to minimize
the total number of teams that are used.

That is, we can define the objective function $\vec{c} \cdot \vec{x}$ as:

$$
\vec{c} \cdot \vec{x} = \sum_{t \in T} C_t
$$

(Here, $\vec{c}$ is a vector where each component is 1).

### Trust EXP problem, formally

With all the pieces in place, we can state the Trust EXP problem as the
following ILP problem:

> Given the minimum number of runs per character $M$ and a set of teams $T$,
> find the team count vector $\vec{x}$ that minimizes $\sum_{t \in T} C_t$,
> subject to the constraints
>
> $$
> \begin{align*}
> \sum_{t \in T} I[c \in t]C_t \geq M \\
> C_t \geq 0 \text{ for all } t
> \end{align*}
> $$

## A Python implementation using Google OR-Tools

Although ILP has been proven to be NP-complete problem, there are off-the-shelf
algorithms and tools that can solve it efficiently in practice.
[Google OR-Tools][ortools] is a library that provides an interface to various
tools that solve optimization problems, including ILP.
I'll use OR-Tools' CP-SAT solver and the Python bindings to demonstrate how we
can compute optimal strategies for the Trust EXP problem.

We start by inputting the Trust NPC data into an array, based on the table shown
[on the ConsoleGames Wiki][trust-npc-table].
If you are trying to run the code yourself, you'll need to have the `ortools`
package installed in your virtualenv (you _are_ using a virtualenv, _right_? 🥹).

???+ note "Python code to initialize the NPC data"
    ```python
    import bisect
    import itertools
    import math

    from dataclasses import dataclass
    from ortools.linear_solver import pywraplp


    # Define a dataclass for NPC.
    # Frozen is set to true so we get hashing for free.
    @dataclass(frozen=True)
    class NPC():
        name: str
        is_tank: bool = False
        is_healer: bool = False
        is_dps: bool = False

    npcs = [
        NPC(name="Alphinaud", is_healer=True, is_dps=True),
        NPC(name="Alisaie", is_dps=True),
        NPC(name="Thancred", is_tank=True),
        NPC(name="Urianger", is_healer=True),
        NPC(name="Yshtola", is_dps=True),
        NPC(name="GrahaTia", is_tank=True, is_healer=True, is_dps=True),
        NPC(name="Estinien", is_dps=True),
        NPC(name="Krile", is_dps=True),
    ]
    tank = [c for c in npcs if c.is_tank]
    healer = [c for c in npcs if c.is_healer]
    dps = [c for c in npcs if c.is_dps]
    ```

Then we need to enumerate all possible teams.
???+ note "Python code to enumerate possible teams"
    ```python
    teams: list[tuple] = []
    npc_teams: dict[NPC, list[int]] = {}

    def add_team_if_valid(c1: NPC, c2: NPC, c3: NPC):
        if c1 == c2 or c1 == c3 or c2 == c3:
            return

        i = len(teams)
        teams.append((c1, c2, c3))
        npc_teams.setdefault(c1, list()).append(i)
        npc_teams.setdefault(c2, list()).append(i)
        npc_teams.setdefault(c3, list()).append(i)

    # Teams where player is tank
    for h, d1, d2 in itertools.product(healer, dps, dps):
        add_team_if_valid(h, d1, d2)
    for t in tank:
        for d1idx, d1 in enumerate(dps):
            # Teams where player is healer
            for d2 in itertools.islice(dps, d1idx, len(dps)):
                add_team_if_valid(t, d1, d2)
            # Teams where player is dps
            for h in healer:
                add_team_if_valid(t, h, d1)

    print(f"Total number of NPCs: {len(npcs)}")
    print(f"Total number of possible teams: {len(teams)}")
    ```

We are now prepared to instantiate our ILP instance and solve it.
The code turns out to be quite simple; the hard part was coming up with the math
earlier.

???+ note "Python code to build the ILP instance and invoke the solver"
    ```python
    solver = pywraplp.Solver.CreateSolver("SAT")
    if not solver:
        print("Failed to initialize solver")
        return

    # Create variables representing the number of times a team is used.
    # Require that the value of each variable is >= 0
    inf = solver.infinity()
    tcounts = [solver.IntVar(0, inf, f"count_{i}") for i in range(0, len(teams))]

    # Constrain the total number of times an NPC is picked to be above the
    # minimum amount required for level up.
    for npc, team_list in npc_teams.items():
        solver.Add(sum(tcounts[t] for t in team_list) >= min_runs)

    # We want to minimize the total number of runs we need to do.
    solver.Minimize(sum(tcounts))

    result_status = solver.Solve()
    if result_status == pywraplp.Solver.FEASIBLE:
        print("A potentially suboptimal solution was found")
    elif result_status != pywraplp.Solver.OPTIMAL:
        print("The solver could not solve the problem.")
        return

    print("Solution:")
    print("Total number of runs:", solver.Objective().Value())
    print("Teams:")
    for t, count_var in enumerate(tcounts):
        count = count_var.solution_value()
        if count <= 0:
            continue
        team = teams[t]
        label = ", ".join(c.name for c in team)
        print(f"{label}: {count}")
    ```

Going from level 91 to 92 would take 3 minimum runs per NPC, with the optimal
strategy requiring a total of 8 runs.

???+ info "Output using $M = 3$"
    ```text
    Total number of NPCs: 8
    Total number of possible teams: 145
    Solution:
    Total number of runs: 8.0
    Teams:
    Alphinaud, Alisaie, GrahaTia: 2.0 runs
    Urianger, Yshtola, Alisaie: 1.0 runs
    Urianger, Krile, Estinien: 2.0 runs
    Thancred, Alphinaud, Yshtola: 1.0 runs
    Thancred, Yshtola, Krile: 1.0 runs
    Thancred, GrahaTia, Estinien: 1.0 runs
    ```

...which would take approximately 4 hours in total.

### Automatically computing minimum runs

Currently, we need to manually specify the minimum number of runs in order to
compute the optimal strategy.
However, it's easier to think of the strategy in terms of what level to start
with and what level we want to end at.
With just the starting and ending level, we can automatically compute the
minimum runs per character as long as we have the [EXP table][level-exp-table]
for each character level and the [Trust EXP table][trust-exp-table] from
ConsoleGames Wiki.
Specifically, we can divide the EXP required to reach the next level by the
amount of EXP awarded per dungeon run, and then round up:

$$
M_c = \left\lceil\frac{\text{EXP required to reach next level}}{\text{Trust EXP awarded by dungeon}}\right\rceil
$$

With these adjustments and some additions to the output, here are the results
for leveling from 91-92:

???+ info "Output for leveling from 91 to 92"
    ```text
    91 -> 92:
    	Total runs: 8.0
    	Total EXP required per character: 13_659_000
    	Dungeon EXP per run: 5801800
    	Minimum runs per character: 3
    	Alphinaud, Alisaie, GrahaTia: 2.0 runs
    	Urianger, Yshtola, Alisaie: 1.0 runs
    	Urianger, Krile, Estinien: 2.0 runs
    	Thancred, Alphinaud, Yshtola: 1.0 runs
    	Thancred, Yshtola, Krile: 1.0 runs
    	Thancred, GrahaTia, Estinien: 1.0 runs
    Total runs: 8.0
    Total est. time: 4.0 hours
    ```

The above strategy is the same as what was computed previously.

What happens if we spam the level 91 dungeon to reach level 100?
???+ info "Output for leveling from 91 to 100 using the level 91 dungeon"
    ```text
    91 -> 100:
    	Total runs: 80.0
    	Total EXP required per character: 168_763_000
    	Dungeon EXP per run: 5801800
    	Minimum runs per character: 30
    	Alphinaud, Alisaie, GrahaTia: 20.0 runs
    	Alphinaud, Yshtola, Estinien: 5.0 runs
    	Urianger, Yshtola, Alisaie: 10.0 runs
    	Urianger, Krile, Estinien: 15.0 runs
    	Thancred, Urianger, Alphinaud: 5.0 runs
    	Thancred, Yshtola, Krile: 15.0 runs
    	Thancred, GrahaTia, Estinien: 10.0 runs
    Total runs: 80.0
    Total est. time: 40.0 hours
    ```
It would take 80 runs totalling a duration of 40 hours, which is more time than
is required to finish the whole _Dawntrail_ main story!

### Using higher level dungeons for more EXP gain

Besides the fact that no one would enjoy spamming the same dungeon over and over
again, higher level dungeons award significantly more EXP.
So one obvious improvement would be to switch to the higher level dungeons as
they become available.
The level 99 dungeon, for example, awards 55% more EXP than the level 91.
Unfortunately, if we were to model higher level dungeons directly in the ILP
problem, it would require us to introduce some sort of conditional logic since,
the higher level dungeons require all NPCs to reach the minimum level first.

We can compute an approximate solution by splitting the problem of going from
level 91 to 100 into multiple smaller problems.
Specifically, for each "best dungeon" at a given level, we'll compute a separate
strategy:

1. Going from level 91 to 93 using the level 91 dungeon
2. Going from level 93 to level 95 using the level 93 dungeon
3. Going from level 95 to level 97 using the level 95 dungeon
4. Going from level 97 to level 99 using the level 97 dungeon
5. Going from level 99 to level 100 using the level 99 dungeon

This yields a decrease from 80 runs to 66 runs, reducing the time from 40 hours
to 33 hours.

??? info "Strategy for leveling from 91 to 100"
    ```text
    91 -> 93:
    	Total runs: 14.0
    	Total EXP required per character: 29_007_000
    	Dungeon EXP per run: 5801800
    	Minimum runs per character: 5
    	Alphinaud, Alisaie, GrahaTia: 4.0 runs
    	Alphinaud, Yshtola, Estinien: 1.0 runs
    	Urianger, Yshtola, Alisaie: 1.0 runs
    	Urianger, Krile, Estinien: 3.0 runs
    	Thancred, Alisaie, Estinien: 1.0 runs
    	Thancred, Yshtola, Krile: 2.0 runs
    	Thancred, Urianger, Yshtola: 1.0 runs
    	Thancred, GrahaTia, Estinien: 1.0 runs
    93 -> 95:
    	Total runs: 14.0
    	Total EXP required per character: 33_446_000
    	Dungeon EXP per run: 6689500
    	Minimum runs per character: 5
    	Urianger, Yshtola, GrahaTia: 1.0 runs
    	Urianger, Estinien, Alisaie: 2.0 runs
    	Urianger, Krile, Alphinaud: 2.0 runs
    	Thancred, Alphinaud, Alisaie: 2.0 runs
    	Thancred, GrahaTia, Alphinaud: 1.0 runs
    	Thancred, Yshtola, Krile: 3.0 runs
    	GrahaTia, Alisaie, Estinien: 1.0 runs
    	GrahaTia, Yshtola, Estinien: 2.0 runs
    95 -> 97:
    	Total runs: 14.0
    	Total EXP required per character: 38_585_000
    	Dungeon EXP per run: 7718100
    	Minimum runs per character: 5
    	Alphinaud, Alisaie, Yshtola: 2.0 runs
    	Urianger, Alisaie, Alphinaud: 2.0 runs
    	GrahaTia, Krile, Estinien: 5.0 runs
    	Thancred, Alphinaud, Alisaie: 2.0 runs
    	Thancred, Urianger, Yshtola: 3.0 runs
    97 -> 99:
    	Total runs: 16.0
    	Total EXP required per character: 43_936_000
    	Dungeon EXP per run: 8787000
    	Minimum runs per character: 6
    	Alphinaud, Alisaie, GrahaTia: 4.0 runs
    	Alphinaud, Yshtola, Estinien: 1.0 runs
    	Urianger, Yshtola, Alisaie: 2.0 runs
    	Urianger, Krile, Estinien: 3.0 runs
    	Thancred, Urianger, Alphinaud: 1.0 runs
    	Thancred, Yshtola, Krile: 3.0 runs
    	Thancred, GrahaTia, Estinien: 2.0 runs
    99 -> 100:
    	Total runs: 8.0
    	Total EXP required per character: 23_789_000
    	Dungeon EXP per run: 9012200
    	Minimum runs per character: 3
    	Alphinaud, Alisaie, GrahaTia: 2.0 runs
    	Urianger, Yshtola, Alisaie: 1.0 runs
    	Urianger, Krile, Estinien: 2.0 runs
    	Thancred, Alphinaud, Yshtola: 1.0 runs
    	Thancred, Yshtola, Krile: 1.0 runs
    	Thancred, GrahaTia, Estinien: 1.0 runs
    Total runs: 66.0
    Total est. time: 33.0 hours
    ```

### Accounting for carry-over EXP

One of the assumptions we've been relying on up to this point is that each
character starts with 0 EXP accumulated.
This is making the computed strategy _worse_ than it should be: since the
minimum runs will award more EXP to a character than is required, each character
will have already made some progress towards the _next_ set of runs.
For example, to go from level 91 to level 93, each character will need to be
included in at least 5 runs of the level 91 dungeon.
The 5 minimum runs will yield a total of 29,009,000 EXP, which is 2,000 more EXP
than is required.
The "excess" amount can be even higher for characters that are used more than
the minimum:
in the above strategy, Alisaie will participate in 6 runs, meaning that she will
have been awarded 5,803,800 above the minimum required EXP.

The excess EXP amount effectively should be "carried over" towards the next set
of dungeon runs.
To model this, we can track the amount of _carry-over_ EXP for each character:

* Initially, the carry-over amount is set to 0 for all characters.
* We now calculate the minimum number of runs (1) separately for each character
  and (2) using the formula

  $$
  M_c = \left\lceil\frac{\text{EXP required to reach next level} - \text{carry-over EXP for } c}{\text{Trust EXP awarded by dungeon}}\right\rceil
  $$

* After computing a strategy for one dungeon, the new carry-over amount is
  computed as

  $$
  \begin{align*}
  \text{carry-over EXP for } c
  &= \text{number of teams containing } c \times \text{Trust EXP per run} \\
  &+ \text{prior carry-over EXP for } c \\
  &- \text{EXP required for levelling}
  \end{align*}
  $$

??? info "Output for leveling from 91 to 100 with carry-over EXP"
    ```text
    91 -> 93:
    	Total runs: 14.0
    	Total EXP required per character: 29_007_000
    	Dungeon EXP per run: 5801800
    	Minimum runs per character: 5
    	Carryover EXP: [2000.0, 5803800.0, 2000.0, 2000.0, 2000.0, 2000.0, 5803800.0, 2000.0]
    	Alphinaud, Alisaie, GrahaTia: 4.0 runs
    	Alphinaud, Yshtola, Estinien: 1.0 runs
    	Urianger, Yshtola, Alisaie: 1.0 runs
    	Urianger, Krile, Estinien: 3.0 runs
    	Thancred, Alisaie, Estinien: 1.0 runs
    	Thancred, Yshtola, Krile: 2.0 runs
    	Thancred, Urianger, Yshtola: 1.0 runs
    	Thancred, GrahaTia, Estinien: 1.0 runs
    93 -> 95:
    	Total runs: 14.0
    	Total EXP required per character: 33_446_000
    	Dungeon EXP per run: 6689500
    	Minimum runs per character: 5
    	Carryover EXP: [3500.0, 5805300.0, 6693000.0, 3500.0, 6693000.0, 3500.0, 5805300.0, 3500.0]
    	Urianger, Yshtola, GrahaTia: 1.0 runs
    	Urianger, Estinien, Alisaie: 2.0 runs
    	Urianger, Krile, Alphinaud: 2.0 runs
    	Thancred, Alphinaud, Alisaie: 2.0 runs
    	Thancred, GrahaTia, Alphinaud: 1.0 runs
    	Thancred, Yshtola, Krile: 3.0 runs
    	GrahaTia, Alisaie, Estinien: 1.0 runs
    	GrahaTia, Yshtola, Estinien: 2.0 runs
    95 -> 97:
    	Total runs: 14.0
    	Total EXP required per character: 38_585_000
    	Dungeon EXP per run: 7718100
    	Minimum runs per character: 5
    	Carryover EXP: [9000.0, 13528900.0, 6698500.0, 9000.0, 6698500.0, 9000.0, 13528900.0, 9000.0]
    	Alphinaud, Alisaie, GrahaTia: 4.0 runs
    	Alphinaud, Yshtola, Estinien: 1.0 runs
    	Urianger, Yshtola, Alisaie: 1.0 runs
    	Urianger, Krile, Estinien: 3.0 runs
    	Thancred, Alisaie, Estinien: 1.0 runs
    	Thancred, Yshtola, Krile: 2.0 runs
    	Thancred, Urianger, Yshtola: 1.0 runs
    	Thancred, GrahaTia, Estinien: 1.0 runs
    97 -> 99:
    	Total runs: 13.0
    	Total EXP required per character: 43_936_000
    	Dungeon EXP per run: 8787000
    	Minimum runs per character: 6
    	Carryover EXP: [8000.0, 4740900.0, 6697500.0, 8795000.0, 6697500.0, 8000.0, 4740900.0, 8000.0]
    	Alphinaud, Estinien, Krile: 1.0 runs
    	Urianger, Alisaie, Krile: 1.0 runs
    	Urianger, Yshtola, Alphinaud: 2.0 runs
    	GrahaTia, Alisaie, Yshtola: 1.0 runs
    	Thancred, Alphinaud, Alisaie: 1.0 runs
    	Thancred, Urianger, Alisaie: 1.0 runs
    	Thancred, Yshtola, Krile: 2.0 runs
    	Thancred, GrahaTia, Estinien: 1.0 runs
    	GrahaTia, Urianger, Estinien: 2.0 runs
    	GrahaTia, Alphinaud, Krile: 1.0 runs
    99 -> 100:
    	Total runs: 7.0
    	Total EXP required per character: 23_789_000
    	Dungeon EXP per run: 9012200
    	Minimum runs per character: 3
    	Carryover EXP: [3255600.0, 7988500.0, 932900.0, 3030400.0, 932900.0, 3255600.0, 7988500.0, 3255600.0]
    	Alphinaud, Alisaie, Estinien: 1.0 runs
    	Urianger, Estinien, Alisaie: 1.0 runs
    	Urianger, Krile, Alphinaud: 1.0 runs
    	GrahaTia, Alisaie, Yshtola: 1.0 runs
    	GrahaTia, Estinien, Krile: 1.0 runs
    	Thancred, Yshtola, Krile: 1.0 runs
    	Thancred, Alphinaud, GrahaTia: 1.0 runs
    Total runs: 62.0
    Total est. time: 31.0 hours
    ```

With carry-over EXP considered, the strategy has improved to 62 runs, with a
total duration of 31 hours.

### Miscellaneous fun facts

How well does the ILP method compare to the known strategies?
I couldn't find any known strategies for _Dawntrail_ likely due to its release
being too recent, but there are known strategies for the _Endwalker_ dungeons.

The best known existing strategy for the _Endwalker_ dungeons [that I was able
to find online][ew-strat] uses a total of 38 runs.
The calculator described in this article finds a strategy involving 25 runs,
which is quite a bit better.
The main difference is that the 38-run strategy assumes the player only chooses
a tank role or a DPS role, while the 25-run strategy assumes the player can play
any role.
Excluding the healer role excludes a larger number of better strategies, though
I understand why someone might not be interested in playing as a healer in
Trust dungeons...

## Was this worth the time?

In total, I spent around an afternoon writing the calculator and had a lot of
fun in the process (more than I did playing the game).
A manual heuristic-based implementation probably would have taken much longer to
develop.
I _did_ spend way more hours writing this article, but I am happy as long as you
enjoyed reading it or at least learned something.
(If you liked my article, feel free to email me with any comments or questions.
You can find my email address on the home page.)

Would it be worth spending 31 hours trying to level up all Trust NPCs from 91 to
100?
It might be a nice side goal if you primarily play DPS roles and you don't want
to bother with long matchmaking queues.

In any case, you can find the source code for the calculator
[here][calculator-sources].


[ew-strat]: https://reddit.com/r/ffxiv/comments/tdli9c/optimal_and_less_complicate_trust_levelling_guide/
[level-exp-table]: https://ffxiv.consolegameswiki.com/wiki/Experience#Required_EXP_by_Level
[trust-exp-table]: https://ffxiv.consolegameswiki.com/wiki/Trust#Compatible_Duties
[trust-npc-table]: https://ffxiv.consolegameswiki.com/wiki/Trust#NPC_Avatars
[ortools]: https://developers.google.com/optimization/introduction
[calculator-sources]: https://github.com/Technius/ffxiv-trust-calc
