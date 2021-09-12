[circleci-badge]: https://circleci.com/gh/hodur-org/hodur-lacinia-schema.svg?style=shield&circle-token=86a1a26155a45d7ec4aba873e975b15ce37d1f5a
[circleci]: https://circleci.com/gh/hodur-org/hodur-lacinia-schema
[clojars-badge]: https://img.shields.io/clojars/v/hodur/lacinia-schema.svg
[clojars]: http://clojars.org/hodur/lacinia-schema
[github-issues]: https://github.com/hodur-org/hodur-lacinia-schema/issues
[graphql]: https://graphql.org/
[hodur-engine]: https://github.com/hodur-org/hodur-engine
[hodur-engine-clojars-badge]: https://img.shields.io/clojars/v/hodur/engine.svg
[hodur-engine-clojars]: http://clojars.org/hodur/engine
[hodur-engine-definition]: https://github.com/hodur-org/hodur-engine#model-definition
[hodur-engine-started]: https://github.com/hodur-org/hodur-engine#getting-started
[lacinia]: https://github.com/walmartlabs/lacinia
[license-badge]: https://img.shields.io/badge/license-MIT-blue.svg
[license]: ./LICENSE
[logo]: ./docs/logo-tag-line.png
[motivation]: https://github.com/hodur-org/hodur-engine/blob/master/docs/MOTIVATION.org
[plugins]: https://github.com/hodur-org/hodur-engine#hodur-plugins
[status-badge]: https://img.shields.io/badge/project%20status-beta-brightgreen.svg

# Hodur Lacinia Schema

[![CircleCI][circleci-badge]][circleci]
[![Clojars][hodur-engine-clojars-badge]][hodur-engine-clojars]
[![Clojars][clojars-badge]][clojars]
[![License][license-badge]][license]
![Status][status-badge]

![Logo][logo]

Hodur is a descriptive domain modeling approach and related collection
of libraries for Clojure.

By using Hodur you can define your domain model as data, parse and
validate it, and then either consume your model via an API making your
apps respond to the defined model or use one of the many plugins to
help you achieve mechanical, repetitive results faster and in a purely
functional manner.

> This Hodur plugin provides the ability to generate
> [Lacinia][lacinia] schemas out of your Hodur model. Lacinia will let
> you spin off a [GraphQL server][graphql] in minutes.

## Motivation

For a deeper insight into the motivations behind Hodur, check the
[motivation doc][motivation].

## Getting Started

Hodur has a highly modular architecture. [Hodur Engine][hodur-engine]
is always required as it provides the meta-database functions and APIs
consumed by plugins.

Therefore, refer the [Hodur Engine's Getting
Started][hodur-engine-started] first and then return here for
Datomic-specific setup.

After having set up `hodur-engine` as described above, we also need to
add `hodur/lacinia-schema`, a plugin that creates Lacinia Schemas out
of your model to the `deps.edn` file:

``` clojure
  {:deps {hodur/engine         {:mvn/version "0.1.7"}
          hodur/lacinia-schema {:mvn/version "0.1.3"}}}
```

You should `require` it any way you see fit:

``` clojure
  (require '[hodur-engine.core :as hodur])
  (require '[hodur-lacinia-schema.core :as hodur-lacinia])
```

Let's expand our `Person` model from the original getting started by
"tagging" the `Person` entity for Lacinia. You can read more about the
concept of tagging for plugins in the sessions below but, in short,
this is the way we, model designers, use to specify which entities we
want to be exposed to which plugins.

``` clojure
  (def meta-db (hodur/init-schema
                '[^{:lacinia/tag-recursive true}
                  Person
                  [^String first-name
                   ^String last-name]]))
```

The `hodur-lacinia-schema` plugin exposes a function called `schema`
that generates your model as a Lacinia schema payload:

``` clojure
  (def lacinia-schema (hodur-lacinia/schema meta-db))
```

When you inspect `lacinia-schema`, this is what you have:

``` clojure
  {:objects
   {:Person
    {:fields
     {:firstName {:type (non-null String)},
      :lastName {:type (non-null String)}}}}}
```

Assuming Lacinia's `com.walmartlabs.lacinia.schema` is bound to
`schema`, you can initialize your instance by compiling the schema like this:

``` clojure
  (def compiled-schema (-> lacinia-schema
                           schema/compile))
```

Most certainly you will have some resolvers defined in your schema
(say `:person-query/resolver` that you want to bind to function
`person-query-resolver`). In this case, attach the resolvers using
Lacinia's `com.walmartlabs.lacinia.util/attach-resolvers` function
(shown in this next example as bound to `util/attach-resolvers`:

``` clojure
  (def compiled-schema (-> lacinia-schema
                           (util/attach-resolvers
                            {:person-query/resolver person-query-resolver})
                           schema/compile))
```

You can also use `com.walmartlabs.lacinia.util/inject-resolvers`
instead if you prefer to keep your schema free of resolver markers.

## Model Definition

All Hodur plugins follow the [Model
Definition][hodur-engine-definition] as described on Hodur [Engine's
documentation][hodur-engine].

## Query, Mutation, and Subscription Roots

GraphQL is not a pure graph interface in the sense of enabling
consumers to start traversing from any node. Instead, it has the
concept of "roots" where queries, mutations, or subscriptions can
start.

To define a query root, use the marker `:lacinia/query`. In the
example below we are defining an entity named `QueryRoot` marked as
Lacinia's query root. It has a single field `game-by-id` that returns
a `BoardGame`.

``` clojure
  [^{:lacinia/tag-recursive true
     :lacinia/query true}
   QueryRoot
   [^BoardGame game-by-id [^{:type ID
                             :optional true} id]]]
```

The same principle applies to mutations and subscriptions. A root
entity must be defined for each and marked with `:lacinia/mutation`
and `:lacinia/subscription` respectively.

## Resolvers and Streamers

In order to provide functionality to your GraphQL interface you will
need to create resolvers and attach them to your graph tree. Lacinia
will take care of building the call stack and stitching up the
response.

A resolver is defined by using the marker `:lacinia/resolve` that can
be used in any field. This marker takes a key that will later be used
by `com.walmartlabs.lacinia.util/attach-resolvers` to map to real
functions. The following example shows how to mark the `game-by-id`
field to the resolver `:query/game-by-id`:

``` clojure
  [^:lacinia/query
   QueryRoot
   [^{:type BoardGame
      :lacinia/resolve :query/game-by-id}
    game-by-id [^{:type ID
                  :optional true} id]]]
```

Subscriptions use streamer functions instead of resolvers. Lacinia
invokes a streamer function once, to initialize the subscription
stream. The streamer is provided with a source stream callback
function; as new values are available they are passed to this
callback. Typically, the streamer will create a thread, `core.async`
process, or other long-lived construct to feed values to the source
stream.

Streamers are defined by using the marker `:lacinia/stream`:

``` clojure
  [^:lacinia/subscription
   SubscriptionRoot
   [^{:type Person
      :lacinia/stream :person/stream}
    listen-to-person [^ID id]]]
```

Both resolvers and streamers can also be attached to your Lacinia
schema much later using
`com.walmartlabs.lacinia.util/inject-resolvers` and
`com.walmartlabs.lacinia.util/inject-streamers` instead. This is ideal
if you prefer to keep your schema free of resolver and streamer
markers.

## Interfaces, Unions, and Enums

GraphQL supports interfaces, unions and enums. Simply marking your
entities accordingly is enough to signal to Hodur Lacinia Schema that
you want to use them.

Refer to [Hodur Engine's Model Definition
documentation][hodur-engine-definition] for more details.

## Input Objects

GraphQL requires that objects that are sent as parameters to mutations
be defined as separate entities.

In the Hodur Lacinia schema this can be drastically simplified by
using the marker `:lacinia/input` on the entity you want to use as an
input object as shown below:

``` clojure
  [^{:lacinia/tag-recursive true
     :lacinia/input true}
   Employee
   [^{:type String} name
    ^{:type Float}  salary]]
```

## Optional and Default Params

By default, Hodur assumes that all parameters are mandatory. In order
to make them optional, they need to be marked with `:optional`. A
common pattern is to make a parameter optional while also assigning a
default value to it with `:default`:

``` clojure
  [QueryRoot
   [employees-by-location [^{:type String
                             :optional true
                             :default "HQ"} location]]]
```

## GraphQL Directives

Hodur supports marking types, fields, enums, enum values, and params
with GraphQL directives through the use of the tag
`:lacinia/directives`.

A common usage is when using GraphQL federation where an internal
entity needs to have a `@key` tag with a `fields` argument that
indicates the key of this entity:

``` clojure
[^{:lacinia/tag-recursive true
   :lacinia/directives [{:key {:fields "id"}}]}
 Employee
 [^{:type ID} id
  ^{:type String} name
  ^{:type Float}  salary]]
```

Hodur supports either a map with a single entry where the key of the
entry is the name of the directive and its value is a map of directive
arguments (as shown above) or a simple keyword for a directive without
arguments. I.e. consider marking the `id` field with hypothetical
`important` and `external` directives:

``` clojure
[^{:lacinia/tag-recursive true}
 Employee
 [^{:type ID
    :lacinia/directives [:important :external]} id
  ^{:type String} name
  ^{:type Float}  salary]]
```


## Bugs

If you find a bug, submit a [GitHub issue][github-issues].

## Help!

This project is looking for team members who can help this project
succeed! If you are interested in becoming a team member please open
an issue.

## License

Copyright © 2019 Tiago Luchini

Distributed under the MIT License (see [LICENSE][license]).
