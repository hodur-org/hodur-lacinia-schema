(ns hodur-lacinia-schema.core
  (:require [anomalies.core :as anom]
            [camel-snake-kebab.core :refer [->camelCaseKeyword
                                            ->PascalCaseKeyword
                                            ->SCREAMING_SNAKE_CASE_KEYWORD
                                            ->camelCaseString
                                            ->PascalCaseString
                                            ->SCREAMING_SNAKE_CASE_STRING]]
            [clojure.string :as s]
            [datascript.core :as d]
            [datascript.query-v3 :as q]))

(def ^:private primitive-type-map
  {"String"   'String
   "Integer"  'Int
   "Float"    'Float
   "Boolean"  'Boolean
   "ID"       'ID
   "DateTime" 'String})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private get-type-reference
  [{:keys [type/name type/nature]}]
  (if (= :user nature)
    (->PascalCaseKeyword name)
    (get primitive-type-map name)))

(defn ^:private cardinality-one-to-one? [cardinality]
  (and (= (first cardinality) 1)
       (or (nil? (second cardinality))
           (= (second cardinality) 1))))

(defn ^:private get-full-type [type optional cardinality]
  (let [non-null-inner-type (list 'non-null (get-type-reference type))
        list-type (list 'list non-null-inner-type)
        inner-type (if optional
                     (get-type-reference type)
                     non-null-inner-type)]
    (if (and (some? cardinality)
             (not (cardinality-one-to-one? cardinality)))
      (if optional
        list-type
        (list 'non-null list-type))
      inner-type)))

(defn ^:private get-field-type
  [{:keys [field/optional field/type field/cardinality]}]
  (get-full-type type optional cardinality))

(defn ^:private get-param-type
  [{:keys [param/optional param/type param/cardinality]}]
  (get-full-type type optional cardinality))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private parse-directives
  [directives]
  (->> directives
       (mapv (fn [directive]
               (if (map? directive)
                 {:directive-type (key (first directive))
                  :directive-args (val (first directive))}
                 {:directive-type directive})))))

(defn ^:private parse-param
  [{:keys [param/doc param/deprecation param/default lacinia/directives] :as param}]
  (cond-> {:type (get-param-type param)}
    default     (assoc :default-value default)
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    directives  (assoc :directives (->> directives parse-directives))))

(defn ^:private parse-params
  [params]
  (reduce (fn [m {:keys [param/name lacinia/tag] :as param}]
            (if tag
              (assoc m (->camelCaseKeyword name) (parse-param param))
              m))
          {} params))

(defn ^:private parse-field
  [{:keys [field/doc field/deprecation param/_parent
           lacinia/resolve lacinia/stream lacinia/directives] :as field}]
  (cond-> {:type (get-field-type field)}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    resolve     (assoc :resolve resolve)
    stream      (assoc :stream stream)
    _parent     (assoc :args (->> _parent (sort-by :param/name) parse-params))
    directives  (assoc :directives (->> directives parse-directives))))

(defn ^:private parse-fields
  [fields]
  (reduce (fn [m {:keys [field/name lacinia/tag] :as field}]
            (if tag
              (assoc m (->camelCaseKeyword name) (parse-field field))
              m))
          {} fields))

(defn ^:private parse-enum-field
  [{:keys [field/name field/doc field/deprecation lacinia/directives] :as field}]
  (cond-> {:enum-value (->SCREAMING_SNAKE_CASE_KEYWORD name)}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    directives  (assoc :directives (->> directives parse-directives))))

(defn ^:private parse-enum-fields
  [fields]
  (reduce (fn [c field]
            (conj c (parse-enum-field field)))
          [] fields))

(defn ^:private parse-union-field
  [{:keys [field/name] :as field}]
  (->PascalCaseKeyword name))

(defn ^:private parse-union-fields
  [fields]
  (reduce (fn [c field]
            (conj c (parse-union-field field)))
          [] fields))

(defn ^:private parse-implement-types
  [types]
  (->> types
       (map #(->PascalCaseKeyword (:type/name %)))
       vec))

(defn ^:private parse-type
  [{:keys [field/_parent type/doc type/deprecation type/implements lacinia/directives]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :fields (->> _parent (sort-by :field/name) parse-fields))
    implements  (assoc :implements (->> implements (sort-by :type/name) parse-implement-types))
    directives  (assoc :directives (->> directives parse-directives))))

(defn ^:private type-sdl-ref [t]
  (let [t' (if (seq? t) t [t])]
    (loop [a "" i (first t') n (next t')]
      (let [[a' n'] (cond
                      (= 'list i)
                      [(str a "[" (type-sdl-ref (first n)) "]") (next n)]

                      (= 'non-null i)
                      [(str a (type-sdl-ref (first n)) "!") (next n)]

                      :else
                      [(str a (name i)) n])]
        (if n'
          (recur a' (first n') (next n'))
          a')))))

(defn ^:private directives-sdl [{:keys [directives deprecated] :as t}]
  (let [directives' (cond-> directives
                      deprecated (conj {:directive-type :deprecated
                                        :directive-args {:reason deprecated}}))]
    (->> directives'
         (map (fn [{:keys [directive-type directive-args]}]
                (str "@" (name directive-type)
                     (when directive-args
                       (str "("
                            (s/join ", "
                                    (map (fn [[kf kv]]
                                           (str (name kf) ": " (if (string? kv)
                                                                 (str "\"" kv "\"")
                                                                 (str kv))))
                                         directive-args))
                            ")")))))
         (s/join " ")
         (#(if (not (empty? %)) (str " " %) "")))))

(defn ^:private tabulator-sdl [tab-size]
  (s/join (repeat tab-size " ")))

(defn ^:private doc-sdl [tab-size t]
  (let [doc (or (:type/doc t) (:field/doc t) (:param/doc t) (:description t))
        tab (tabulator-sdl tab-size)]
    (if doc
      (->> [(str tab "\"\"\"")
            (str tab doc)
            (str tab "\"\"\"")
            tab]
           (s/join "\n"))
      tab)))

(defn ^:private parse-args-sdl [{:keys [args]}]
  (if (empty? args)
    ""
    (str "(\n" (s/join "\n" (map (fn [[n {:keys [type default-value] :as arg}]]
                                   (let [default-str (if default-value
                                                       (if (string? default-value)
                                                         (str " = \"" default-value "\"")
                                                         (str " = " default-value))
                                                       "")]
                                     (str (doc-sdl 4 arg)
                                          (->camelCaseString n)
                                          ": "
                                          (type-sdl-ref type)
                                          (directives-sdl arg)
                                          default-str))) args))
         "\n" (tabulator-sdl 2) ")")))

(defn ^:private parse-type-sdl [t]
  (s/join "\n" (map (fn [[n {:keys [type] :as field}]]
                      (str (doc-sdl 2 field) (->camelCaseString n)
                           (parse-args-sdl field) ": " (type-sdl-ref type) (directives-sdl field)))
                    (:fields (parse-type t)))))

(defn ^:private parse-enum
  [{:keys [field/_parent type/doc type/deprecation lacinia/directives]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :values (->> _parent (sort-by :field/name) parse-enum-fields))
    directives  (assoc :directives (->> directives parse-directives))))

(defn ^:private parse-enum-sdl [t]
  (s/join "\n" (map (fn [{:keys [enum-value] :as enum}]
                      (str (doc-sdl 2 enum)
                           (->SCREAMING_SNAKE_CASE_STRING enum-value)
                           (directives-sdl enum)))
                    (:values (parse-enum t)))))

(defn ^:private parse-union
  [{:keys [field/_parent type/doc type/deprecation]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :members (->> _parent (sort-by :field/name) parse-union-fields))))

(defn ^:private parse-union-sdl [t]
  (s/join " | " (map (fn [v]
                       (->PascalCaseString v))
                     (:members (parse-union t)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private reduce-type-fields
  [m {:keys [field/_parent] :as t}]
  (->> _parent
       (sort-by :field/name)
       (reduce (fn [f-m {:keys [lacinia/tag] :as field}]
                 (if tag
                   (assoc f-m (-> field :field/name ->camelCaseKeyword) (parse-field field))
                   f-m))
               m)))

(defn ^:private reduce-type
  [m {:keys [type/name] :as t}]
  (assoc m
         (->PascalCaseKeyword name)
         (parse-type t)))

(defn ^:private implements-sdl [implements]
  (if (not (empty? implements))
    (str " implements " (s/join " & " (map #(->PascalCaseString (:type/name %)) implements)))
    ""))

(defn ^:private type-id [{:keys [type/interface lacinia/input]}]
  (cond
    interface "interface"
    input "input"
    :else "type"))

(defn ^:private reduce-type-sdl
  [m {:keys [type/name type/implements] :as t}]
  (str m (->> [(str "\n\n" (doc-sdl 0 t) (type-id t) " " (->PascalCaseString name)
                    (implements-sdl implements) (directives-sdl (parse-type t))" {")
               (str (parse-type-sdl t))
               "}"]
              (s/join "\n"))))

(defn ^:private initializer-special-types-sdl []
  (->> ["" ""
        "schema {"]
       (s/join "\n")))

(defn ^:private finisher-special-types-sdl []
  (->> ["" "}"]
       (s/join "\n")))

(defn ^:private reduce-special-types-sdl
  [m {:keys [type/name lacinia/query lacinia/mutation lacinia/subscription] :as t}]
  (let [special-key (cond
                      query "query"
                      mutation "mutation"
                      subscription "subscription")]
    (str m (str "\n  " special-key ": " (->PascalCaseString name)))))

(defn ^:private reduce-enum
  [m {:keys [type/name] :as t}]
  (assoc m
         (->PascalCaseKeyword name)
         (parse-enum t)))

(defn ^:private reduce-enum-sdl
  [m {:keys [type/name] :as t}]
  (str m (->> [(str "\n\nenum " (->PascalCaseString name) " {")
               (str (parse-enum-sdl t))
               "}"]
              (s/join "\n"))))

(defn ^:private reduce-union
  [m {:keys [type/name] :as t}]
  (assoc m
         (->PascalCaseKeyword name)
         (parse-union t)))

(defn ^:private reduce-union-sdl
  [m {:keys [type/name] :as t}]
  (str m "\n\nunion " (->PascalCaseString name) " = " (parse-union-sdl t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME kind of a hack because query-v3 does not pull
(defn ^:private find-and-pull
  ([selector where conn]
   (find-and-pull selector where conn nil))
  ([selector where conn sort-by-key]
   (let [eids (-> (q/q (concat '[:find ?e :where] where) @conn)
                  vec flatten)]
     (cond->> eids
       true        (d/pull-many @conn selector)
       sort-by-key (sort-by sort-by-key)))))

(def ^:private selector
  '[* {:type/implements [*]
       :field/_parent
       [* {:field/type [*]
           :param/_parent
           [* {:param/type [*]}]}]}])

(def ^:private section-map
  {:objects
   {:where '[[?e :lacinia/tag true]
             [?e :type/nature :user]
             (not [?e :type/interface true])
             (not [?e :type/enum true])
             (not [?e :type/union true])
             (not [?e :lacinia/query true])
             (not [?e :lacinia/mutation true])
             (not [?e :lacinia/subscription true])
             (not [?e :lacinia/input true])]
    :reducer-lacinia reduce-type}

   :interfaces
   {:where '[[?e :type/interface true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-lacinia reduce-type}

   :enums
   {:where '[[?e :type/enum true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-lacinia reduce-enum}

   :unions
   {:where '[[?e :type/union true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-lacinia reduce-union}

   :input-objects
   {:where '[[?e :lacinia/input true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-lacinia reduce-type}
   
   :queries
   {:where '[[?e :lacinia/query true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-lacinia reduce-type-fields}

   :mutations
   {:where '[[?e :lacinia/mutation true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-lacinia reduce-type-fields}

   :subscriptions
   {:where '[[?e :lacinia/subscription true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-lacinia reduce-type-fields}})


(def ^:private sdl-section-map
  {:types
   {:where '[[?e :lacinia/tag true]
             [?e :type/nature :user]
             (not [?e :type/enum true])
             (not [?e :type/union true])]
    :reducer-sdl reduce-type-sdl}

   :enums
   {:where '[[?e :type/enum true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-sdl reduce-enum-sdl}

   :unions
   {:where '[[?e :type/union true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer-sdl reduce-union-sdl}

   :special-types
   {:where '[[?e :lacinia/tag true]
             [?e :type/nature :user]
             (or [?e :lacinia/query true]
                 [?e :lacinia/mutation true]
                 [?e :lacinia/subscription true])
             (not [?e :type/enum true])
             (not [?e :type/union true])]
    :reducer-sdl reduce-special-types-sdl
    :sdl-initializer initializer-special-types-sdl
    :sdl-finisher finisher-special-types-sdl}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn schema
  ([conn]
   (schema conn {:output :lacinia-schema}))
  ([conn {:keys [output]}]
   (case output
     :lacinia-schema
     (reduce-kv (fn [m k {:keys [where reducer-lacinia]}]
                  (let [types (find-and-pull selector where conn)]
                    (if (or (empty? types))
                      m
                      (assoc m k (reduce (fn [m t]
                                           (reducer-lacinia m t))
                                         {} types)))))
                {} section-map)

     :sdl
     (s/trim
      (reduce-kv (fn [m k {:keys [where reducer-sdl sdl-initializer sdl-finisher]
                           :or {sdl-initializer (constantly "")
                                sdl-finisher (constantly "")}}]
                   (if reducer-sdl
                     (let [types (find-and-pull selector where conn)]
                       (if (empty? types)
                         m
                         (str m
                              (sdl-initializer)
                              (reduce reducer-sdl
                                      "" types)
                              (sdl-finisher))))
                     m))
                 "" sdl-section-map)))))

(comment
  (require '[hodur-engine.core :as engine])

  (def conn (engine/init-schema
             '[^{:lacinia/tag true}
               default

               ^{:doc "A physical or virtual board game."}
               BoardGame
               [^ID id
                ^String name
                ^{:type String
                  :optional true
                  :doc "A one-line summary of the game."}
                summary
                ^{:type String
                  :optional true
                  :doc "A long-form description of the game."}
                description
                ^{:type Integer
                  :optional true
                  :doc "The minimum number of players the game supports."}
                min_players
                ^{:type Integer
                  :optional true
                  :doc "The maximum number of players the game supports."}
                max_players
                ^{:type Integer
                  :optional true
                  :doc "Play time, in minutes, for a typical game."}
                play-time
                ^{:type Float
                  :lacinia/resolve :field/resolver}
                calculated-field]

               ^:interface
               Player
               [^ID id
                ^String name]

               ^{:implements Player
                 :lacinia/directives [{:key {:fields "id"}}]}
               PlayerImpl
               [^ID id
                ^String name
                ^Float height [^{:type Unit
                                 :default FEET} unit]
                ^{:type DateTime
                  :optional true
                  :lacinia/directives [:important :external]} dob]

               ^:enum
               Unit
               [METERS
                ^{:deprecation "No one should use imperial!"} FEET]
               
               ^:enum
               PlayerType
               [^{:doc "Yeah! Those"}
                AMERITRASH
                EUROPEAN]

               ^:union
               SearchResult
               [Player BoardGame]

               ^:lacinia/input
               PlayerInput
               [^String name]

               ^:lacinia/query
               QueryRoot
               [^{:type BoardGame
                  :optional true
                  :doc "Access a BoardGame by its unique id, if it exists."
                  :lacinia/resolve :query/game-by-id
                  :lacinia/directives [:external
                                       {:roles {:type SPECIAL
                                                :values [ADMIN READER]
                                                :id 23}}]}
                game_by_id
                [^{:type ID
                   :optional true
                   :default 3} id]
                ^{:type SearchResult
                  :cardinality [0 n]}
                search
                [^{:type String
                   :deprecation "Don't use this anymore"} term
                 ^{:type String
                   :optional true
                   :doc "Cool arg doc"} cool-arg]]

               ^:lacinia/mutation
               MutationRoot
               [^Boolean reset]]))

  (def s (schema conn {:output :sdl}))

  #_(clojure.pprint/pprint s)
  (println s))
