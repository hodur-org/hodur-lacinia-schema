(ns core-test
  (:require [clojure.test :refer :all]
            [hodur-engine.core :as engine]
            [hodur-lacinia-schema.core :as lacinia]))

(defn ^:private schema->lacinia [s]
  (-> s
      engine/init-schema
      lacinia/schema))

;; Tests:
;; * two basic objects
;; * enum
;; * cardinality
;; * optional field
;; * related entities
(deftest test-basic-objects
  (let [s (schema->lacinia '[^{:lacinia/tag true}
                             default

                             Person
                             [^ID id
                              ^String name
                              ^{:type Relation
                                :optional true
                                :cardinality [0 n]}
                              relations]

                             Relation
                             [^Person with-person
                              ^RelationType type]

                             ^{:enum true}
                             RelationType
                             [SPOUSE PARENT COUSIN SIBLING]])]
    (is (= '{:objects
             {:Person
              {:fields
               {:id {:type (non-null ID)}
                :name {:type (non-null String)}
                :relations {:type (list (non-null :Relation))}}}
              :Relation
              {:fields
               {:type {:type (non-null :RelationType)}
                :withPerson {:type (non-null :Person)}}}}
             :enums
             {:RelationType
              {:values
               [{:enum-value :COUSIN}
                {:enum-value :PARENT}
                {:enum-value :SIBLING}
                {:enum-value :SPOUSE}]}}}
           s))))

;; Tests:
;; * query root marker
;; * parameters
;; * optional parameters
;; * default parameters
(deftest test-query-root
  (let [s (schema->lacinia '[^{:lacinia/tag true}
                             default

                             Person
                             [^ID id
                              ^String name]

                             ^{:lacinia/query true}
                             QueryRoot
                             [^Person 
                              person-by-id [^ID id]

                              ^{:type Person
                                :cardinality [0 n]}
                              search-persons [^{:type String
                                                :optional true
                                                :default ""} term]]])]
    (is (= '{:objects
             {:Person
              {:fields
               {:id {:type (non-null ID)}
                :name {:type (non-null String)}}}}
             :queries
             {:personById
              {:type (non-null :Person)
               :args {:id {:type (non-null ID)}}}
              :searchPersons
              {:type (non-null (list (non-null :Person)))
               :args {:term {:type String
                             :default-value ""}}}}}
           s))))

;; Tests:
;; * query root marker
;; * resolvers
;; * sub-resolvers
(deftest test-resolvers
  (let [s (schema->lacinia '[^{:lacinia/tag true}
                             default

                             Person
                             [^ID id
                              ^{:type String
                                :lacinia/resolve :person/name} name]

                             ^{:lacinia/query true}
                             QueryRoot
                             [^{:type Person
                                :lacinia/resolve :query/person-by-id}
                              person-by-id [^ID id]
                              ^{:type Person
                                :cardinality [0 n]
                                :lacinia/resolve :query/people-by-ids}
                              people-by-ids [^{:type ID
                                               :cardinality [0 n]} ids]]])]
    (is (= '{:objects
             {:Person
              {:fields
               {:id {:type (non-null ID)}
                :name {:type (non-null String)
                       :resolve :person/name}}}}
             :queries
             {:personById
              {:type (non-null :Person)
               :resolve :query/person-by-id
               :args {:id {:type (non-null ID)}}}
              :peopleByIds
              {:type (non-null (list (non-null :Person)))
               :resolve :query/people-by-ids
               :args {:ids {:type (non-null (list (non-null ID)))}}}}}
           s))))

;; Tests:
;; * docs for entities, fields and params
;; * deprecation
(deftest test-docs-and-deprecation
  (let [s (schema->lacinia '[^{:lacinia/tag true}
                             default

                             ^{:doc "This is a person"}
                             Person
                             [^ID id
                              ^{:type String
                                :doc "Person's name"} name
                              ^{:type Float
                                :deprecation "Do not use"} deprecated-field]

                             ^{:lacinia/query true}
                             QueryRoot
                             [^Person person-by-id [^{:type ID
                                                      :doc "ID to search for"} id]]])]
    (is (= '{:objects
             {:Person
              {:description "This is a person"
               :fields
               {:deprecatedField
                {:type (non-null Float), :deprecated "Do not use"}
                :id {:type (non-null ID)}
                :name {:type (non-null String), :description "Person's name"}}}}
             :queries
             {:personById
              {:type (non-null :Person)
               :args
               {:id {:type (non-null ID), :description "ID to search for"}}}}}
           s))))


;; Tests:
;; * Interfaces
(deftest test-interfaces
  (let [s (schema->lacinia '[^{:lacinia/tag true}
                             default

                             ^:interface
                             Animal
                             [^String name]

                             ^{:implements Animal}
                             Cat
                             [^String meow]

                             ^{:implements Animal}
                             Doc
                             [^String bark]])]
    (is (= '{:objects
             {:Cat {:implements [:Animal]
                    :fields {:meow {:type (non-null String)}}}
              :Doc {:implements [:Animal]
                    :fields {:bark {:type (non-null String)}}}}
             :interfaces {:Animal {:fields {:name {:type (non-null String)}}}}}
           s))))

;; Tests:
;; * Unions
(deftest test-unions
  (let [s (schema->lacinia '[^{:lacinia/tag true}
                             default

                             Cat
                             [^String meow]

                             Doc
                             [^String bark]

                             ^:union
                             SearchResults
                             [Cat Dog]])]
    (is (= '{:objects
             {:Cat {:fields {:meow {:type (non-null String)}}}
              :Doc {:fields {:bark {:type (non-null String)}}}}
             :unions {:SearchResults {:members [:Cat :Dog]}}}
           s))))


;; Tests:
;; * mutations
;; * input objects
(deftest test-mutations-input-objects
  (let [s (schema->lacinia '[^{:lacinia/tag true}
                             default

                             Person
                             [^String name]
                             
                             ^:lacinia/input
                             PersonInput
                             [^String name]

                             ^:lacinia/mutation
                             MutationsRoot
                             [^Person
                              update-person [^PersonInput input]]])]
    (is (= '{:objects {:Person {:fields {:name {:type (non-null String)}}}}
             :input-objects
             {:PersonInput {:fields {:name {:type (non-null String)}}}}
             :mutations
             {:updatePerson
              {:type (non-null :Person)
               :args {:input {:type (non-null :PersonInput)}}}}}
           s))))

;; Tests:
;; * subscriptions
;; * streams
(deftest test-subscriptions-streams
  (let [s (schema->lacinia '[^{:lacinia/tag true}
                             default

                             Person
                             [^ID id
                              ^String name]
                             
                             ^:lacinia/subscription
                             SubscriptionRoot
                             [^{:type Person
                                :lacinia/stream :person/stream}
                              listen-to-person [^ID id]]])]
    (is (= '{:objects
             {:Person
              {:fields
               {:id {:type (non-null ID)}
                :name {:type (non-null String)}}}}
             :subscriptions
             {:listenToPerson
              {:type (non-null :Person)
               :stream :person/stream
               :args {:id {:type (non-null ID)}}}}}
           s))))
