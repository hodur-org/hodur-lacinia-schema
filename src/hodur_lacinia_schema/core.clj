(ns hodur-lacinia-schema.core
  (:require [camel-snake-kebab.core :refer [->camelCaseKeyword
                                            ->PascalCaseKeyword
                                            ->SCREAMING_SNAKE_CASE_KEYWORD]]
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
  [opts {:keys [type/name type/nature]}]
  (if (= :user nature)
    (->PascalCaseKeyword name)
    (if-let [custom (get-in opts [:custom-type-map name])]
      custom
      (get primitive-type-map name))))

(defn ^:private cardinality-one-to-one? [cardinality]
  (and (= (first cardinality) 1)
       (or (nil? (second cardinality))
           (= (second cardinality) 1))))

(defn ^:private get-full-type [opts type optional cardinality]
  (let [non-null-inner-type (list 'non-null (get-type-reference opts type))
        list-type (list 'list non-null-inner-type)
        inner-type (if optional
                     (get-type-reference opts type)
                     non-null-inner-type)]
    (if (and (some? cardinality)
             (not (cardinality-one-to-one? cardinality)))
      (if optional
        list-type
        (list 'non-null list-type))
      inner-type)))

(defn ^:private get-field-type
  [opts {:keys [field/optional field/type field/cardinality]}]
  (get-full-type opts type optional cardinality))

(defn ^:private get-param-type
  [opts {:keys [param/optional param/type param/cardinality]}]
  (get-full-type opts type optional cardinality))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private parse-param
  [opts {:keys [param/doc param/deprecation param/default] :as param}]
  (cond-> {:type (get-param-type opts param)}
    default     (assoc :default-value default)
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)))

(defn ^:private parse-params
  [opts params]
  (reduce (fn [m {:keys [param/name lacinia/tag] :as param}]
            (if tag
              (assoc m (->camelCaseKeyword name) (parse-param opts param))
              m))
          {} params))

(defn ^:private parse-field
  [opts {:keys [field/doc field/deprecation param/_parent
           lacinia/resolve lacinia/stream] :as field}]
  (cond-> {:type (get-field-type opts field)}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    resolve     (assoc :resolve resolve)
    stream      (assoc :stream stream)
    _parent     (assoc :args (->> _parent (sort-by :param/name) (parse-params opts)))))

(defn ^:private parse-fields
  [opts fields]
  (reduce (fn [m {:keys [field/name lacinia/tag] :as field}]
            (if tag
              (assoc m (->camelCaseKeyword name) (parse-field opts field))
              m))
          {} fields))

(defn ^:private parse-enum-field
  [opts {:keys [field/name field/doc field/deprecation] :as field}]
  (cond-> {:enum-value (->SCREAMING_SNAKE_CASE_KEYWORD name)}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)))

(defn ^:private parse-enum-fields
  [opts fields]
  (reduce (fn [c field]
            (conj c (parse-enum-field opts field)))
          [] fields))

(defn ^:private parse-union-field
  [opts {:keys [field/name] :as field}]
  (->PascalCaseKeyword name))

(defn ^:private parse-union-fields
  [opts fields]
  (reduce (fn [c field]
            (conj c (parse-union-field opts field)))
          [] fields))

(defn ^:private parse-implement-types
  [types]
  (->> types
       (map #(->PascalCaseKeyword (:type/name %)))
       vec))

(defn ^:prvate parse-type
  [opts {:keys [field/_parent type/doc type/deprecation type/implements]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :fields (->> _parent (sort-by :field/name) (parse-fields opts)))
    implements  (assoc :implements (->> implements (sort-by :type/name) parse-implement-types))))

(defn ^:prvate parse-enum
  [opts {:keys [field/_parent type/doc type/deprecation]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :values (->> _parent (sort-by :field/name) (parse-enum-fields opts)))))

(defn ^:prvate parse-union
  [opts {:keys [field/_parent type/doc type/deprecation]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :members (->> _parent (sort-by :field/name) (parse-union-fields opts)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private reduce-type-fields
  [m {:keys [field/_parent] :as t} opts]
  (->> _parent
       (sort-by :field/name)
       (reduce (fn [f-m {:keys [lacinia/tag] :as field}]
                 (if tag
                   (assoc f-m (-> field :field/name ->camelCaseKeyword) (parse-field opts field))
                   f-m))
               m)))

(defn ^:private reduce-type
  [m {:keys [type/name] :as t} opts]
  (assoc m
         (->PascalCaseKeyword name)
         (parse-type opts t)))

(defn ^:private reduce-enum
  [m {:keys [type/name] :as t} opts]
  (assoc m
         (->PascalCaseKeyword name)
         (parse-enum opts t)))

(defn ^:private reduce-union
  [m {:keys [type/name] :as t} opts]
  (assoc m
         (->PascalCaseKeyword name)
         (parse-union opts t)))

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
    :reducer reduce-type}

   :interfaces
   {:where '[[?e :type/interface true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type}

   :enums
   {:where '[[?e :type/enum true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-enum}

   :unions
   {:where '[[?e :type/union true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-union}

   :input-objects
   {:where '[[?e :lacinia/input true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type}
   
   :queries
   {:where '[[?e :lacinia/query true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type-fields}

   :mutations
   {:where '[[?e :lacinia/mutation true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type-fields}

   :subscriptions
   {:where '[[?e :lacinia/subscription true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type-fields}})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn schema
  ([conn] (schema conn {}))
  ([conn opts] (reduce-kv (fn [m k {:keys [where reducer]}]
               (let [types (find-and-pull selector where conn)]
                 (if (empty? types)
                   m
                   (assoc m k (reduce (fn [m t]
                                        (reducer m t opts))
                                      {} types)))))
             {} section-map)))

(comment
  (do
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
                 [^String name]

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
                    :lacinia/resolve :query/game-by-id}
                  game_by_id
                  [^{:type ID
                     :optional true
                     :default 3} id]
                  ^{:type SearchResult
                    :cardinality [0 n]}
                  search
                  [^String term]]]))

    (def s (schema conn))

    s))

