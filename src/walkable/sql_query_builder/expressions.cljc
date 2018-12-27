(ns walkable.sql-query-builder.expressions
  #?(:cljs (:require-macros [walkable.sql-query-builder.expressions
                             :refer [def-simple-cast-types
                                     import-functions import-infix-operators]]))
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.set :as set]))

(defrecord AtomicVariable [name])

(defn av [n]
  (AtomicVariable. n))

(defn atomic-variable? [x]
  (instance? AtomicVariable x))

(defn expand-atomic-variables [exprs]
  (clojure.walk/postwalk
    (fn [expr] (if (and (symbol? expr) (::variable (meta expr)))
                 (AtomicVariable. expr)
                 expr))
    exprs))

(declare inline-params)

(defn namespaced-keyword?
  [x]
  (and (keyword? x) (namespace x)))

(s/def ::namespaced-keyword namespaced-keyword?)

(defmulti operator? identity)
(defmethod operator? :default [_operator] false)

(s/def ::operators operator?)

(defmulti unsafe-expression? identity)
(defmethod unsafe-expression? :default [_operator] false)

(s/def ::unsafe-expression unsafe-expression?)

(s/def ::expression
  (s/or
    :atomic-variable atomic-variable?
    :nil nil?
    :number number?
    :boolean boolean?
    :string string?
    :column ::namespaced-keyword
    :expression
    (s/and vector?
      (s/cat :operator (s/? ::operators)
        :params (s/* ::expression)))
    :unsafe-expression
    (s/and vector?
      (s/cat :operator ::unsafe-expression
        :params (s/* (constantly true))))
    :join-filters
    (s/coll-of
      (s/or :join-filter
        (s/cat :join-key ::namespaced-keyword
          :expression ::expression))
      :kind map? :into [])))

;; the rule for parentheses in :raw-string
;; outer raw string should provide them
;; inner ones shouldn't

(defmulti process-operator
  (fn dispatcher [_env [operator _params]] operator))

(defmulti process-unsafe-expression
  (fn dispatcher [_env [operator _params]] operator))

(defmulti process-expression
  (fn dispatcher [_env [kw _expression]] kw))

(def conformed-nil
  {:raw-string "NULL"
   :params     []})

(def conformed-true
  {:raw-string "TRUE"
   :params     []})

(def conformed-false
  {:raw-string "FALSE"
   :params     []})

(defmulti cast-type
  "Registers a valid type for for :cast-type."
  (fn [type-kw type-params] type-kw))

(defmethod cast-type :default [_type _type-params] nil)

#?(:clj
   (defmacro def-simple-cast-types
     [{:keys [upper-case?]} keywords]
     (assert (every? keyword? keywords)
       "All arguments must be a keyword.")
     `(do
        ~@(for [k keywords
                :let [symbol-name (clojure.string/replace (name k) #"-" "_")]]
            `(defmethod cast-type ~k [_type# _type-params#]
               ~(if upper-case?
                  (clojure.string/upper-case symbol-name)
                  symbol-name))))))

(def-simple-cast-types {:upper-case? true}
  [:integer :text :date :datetime])

(defmethod unsafe-expression? :cast [_operator] true)

(defmethod process-unsafe-expression :cast
  [env [_operator [expression type-kw type-params]]]
  (let [expression (s/conform ::expression expression)
        type-str   (cast-type type-kw type-params)]
    (assert (not= expression ::s/invalid)
      (str "First argument to `cast` is not an invalid expression."))
    (assert type-str
      (str "Invalid type to `cast`. You may want to implement `cast-type` for the given type."))
    (inline-params env
      {:raw-string (str "CAST (? AS " type-str ")")
       :params     [(process-expression env expression)]})))

(defmethod operator? :and [_operator] true)

(defn single-raw-string [x]
  {:raw-string "?"
   :params     [x]})

(defmethod process-operator :and
  [_env [_operator params]]
  (if (empty? params)
    (single-raw-string true)
    {:raw-string (clojure.string/join " AND "
                   (repeat (count params) "(?)"))
     :params     params}))

(defmethod operator? :or [_operator] true)

(defmethod process-operator :or
  [_env [_operator params]]
  (if (empty? params)
    (single-raw-string false)
    {:raw-string (clojure.string/join " OR "
                   (repeat (count params) "(?)"))
     :params     params}))

(defn multiple-compararison
  "Common implementation of process-operator for comparison operators: =, <, >, <=, >="
  [comparator-string params]
  (assert (< 1 (count params))
    (str "There must be at least two arguments to " comparator-string))
  (let [params (partition 2 1 params)]
    {:raw-string (clojure.string/join " AND "
                   (repeat (count params) (str "(?)" comparator-string "(?)")))
     :params     (flatten params)}))

(defmethod operator? := [_operator] true)

(defmethod process-operator :=
  [_env [_operator params]]
  (multiple-compararison "=" params))

(defmethod operator? :> [_operator] true)

(defmethod process-operator :>
  [_env [_operator params]]
  (multiple-compararison ">" params))

(defmethod operator? :>= [_operator] true)

(defmethod process-operator :>=
  [_env [_operator params]]
  (multiple-compararison ">=" params))

(defmethod operator? :< [_operator] true)

(defmethod process-operator :<
  [_env [_operator params]]
  (multiple-compararison "<" params))

(defmethod operator? :<= [_operator] true)

(defmethod process-operator :<=
  [_env [_operator params]]
  (multiple-compararison "<=" params))

(defn infix-notation
  "Common implementation for +, -, *, /"
  [operator-string params]
  {:raw-string (clojure.string/join operator-string
                 (repeat (count params) "(?)"))
   :params     params})

(defmethod operator? :+ [_operator] true)

(defmethod process-operator :+
  [_env [_operator params]]
  (if (empty? params)
    {:raw-string "0"
     :params     []}
    (infix-notation "+" params)))

(defmethod operator? :* [_operator] true)

(defmethod process-operator :*
  [_env [_operator params]]
  (if (empty? params)
    {:raw-string "1"
     :params     []}
    (infix-notation "*" params)))

(defmethod operator? :- [_operator] true)

(defmethod process-operator :-
  [_env [_operator params]]
  (assert (not (empty? params))
    "There must be at least one parameter to `-`")
  (if (= 1 (count params))
    {:raw-string "0-(?)"
     :params     params}
    (infix-notation "-" params)))

(defmethod operator? :/ [_operator] true)

(defmethod process-operator :/
  [_env [_operator params]]
  (assert (not (empty? params))
    "There must be at least one parameter to `/`")
  (if (= 1 (count params))
    {:raw-string "1/(?)"
     :params     params}
    (infix-notation "/" params)))

(defn one-argument-operator
  [params operator-name raw-string]
  (assert (= 1 (count params))
    (str "There must be exactly one argument to `" operator-name "`."))
  {:raw-string raw-string
   :params     params})

#?(:clj
   (defn operator-name
     [{:keys [prefix]} symbol-name]
     (keyword (if prefix
                (str prefix "." symbol-name)
                symbol-name))))

#?(:clj
   (defn operator-sql-names
     "Helper for import-functions macro."
     [{:keys [upper-case?] :as opts} sym]
     (let [symbol-name (name sym)]
       [;; operator
        (operator-name opts symbol-name)
        ;; fname
        (let [symbol-name (clojure.string/replace symbol-name #"-" "_")]
          (if upper-case?
            (clojure.string/upper-case symbol-name)
            symbol-name))])))

#?(:clj
   (defmacro import-functions
     "Defines Walkable operators using SQL equivalent."
     [{:keys [arity] :as options} function-names]
     `(do
        ~@(for [[operator sql-name] (if (map? function-names)
                                      (mapv (fn [[sym sql-name]] [(operator-name options (name sym)) sql-name]) function-names)
                                      (mapv #(operator-sql-names options %) function-names))]
            `(do
               (defmethod operator? ~operator [_operator#] true)

               ~(case arity
                  0
                  `(defmethod process-operator ~operator
                     [_env# [_operator# params#]]
                     (assert (zero? (count params#))
                       ~(str "There must be no argument to " operator))
                     {:raw-string ~(str sql-name "()")
                      :params     []})
                  1
                  `(defmethod process-operator ~operator
                     [_env# [_operator# params#]]
                     (assert (= 1 (count params#))
                       ~(str "There must exactly one argument to " operator))
                     {:raw-string ~(str sql-name " (?)")
                      :params     params#})
                  ;; default
                  `(defmethod process-operator ~operator
                     [_env# [_operator# params#]]
                     (let [n# (count params#)]
                       {:raw-string (str ~(str sql-name " (")
                                      (clojure.string/join ", "
                                        (repeat n# \?))
                                      ")")
                        :params     params#}))))))))

(import-functions {:arity       1
                   :upper-case? true}
  [sum count not min max avg])

(import-functions {:arity 1 }
  {bit-not "~"})

(import-functions {:arity 0 :upper-case? true}
  [now])

(import-functions {}
  [format])

(import-functions {}
  {str "CONCAT"})

#?(:clj
   (defmacro import-infix-operators
     "Defines Walkable operators using SQL equivalent."
     [{:keys [arity] :as options} operator-names]
     `(do
        ~@(for [[operator sql-name] (if (map? operator-names)
                                      (mapv (fn [[sym sql-name]] [(operator-name options (name sym)) sql-name]) operator-names)
                                      (mapv #(operator-sql-names options %) operator-names))]
            `(do
               (defmethod operator? ~operator [_operator#] true)

               ~(case arity
                  2
                  `(defmethod process-operator ~operator
                     [_env# [_operator# params#]]
                     (assert (= 2 (count params#))
                       ~(str "There must exactly two arguments to " operator))
                     {:raw-string ~(str "(?)" sql-name "(?)")
                      :params     params#})
                  ;; default
                  `(defmethod process-operator ~operator
                     [_env# [_operator# params#]]
                     (let [n# (count params#)]
                       {:raw-string (clojure.string/join ~sql-name
                                      (repeat n# "(?)"))
                        :params     params#}))))))))

(import-infix-operators {}
  [like])

(import-infix-operators {}
  {bit-and "&"
   bit-or  "|"})

(import-infix-operators {:arity 2}
  {bit-shift-left  "<<"
   bit-shift-right ">>"})

;; todo implement COLLATE

(defmethod operator? :count-* [_operator] true)

(defmethod process-operator :count-*
  [_env [_operator params]]
  (assert (empty? params) "count-* doesn't accept arguments")
  {:raw-string "COUNT(*)"
   :params     []})

(defmethod operator? :in [_operator] true)

(defmethod process-operator :in
  [_env [_operator params]]
  {:raw-string (str "(?) IN ("
                 (clojure.string/join ", "
                   ;; decrease by 1 to exclude the first param
                   ;; which should go before `IN`
                   (repeat (dec (count params)) \?))
                 ")")
   :params     params})

(defmethod process-expression :expression
  [env [_kw {:keys [operator params] :or {operator :and}}]]
  (inline-params env
    (process-operator env
      [operator (mapv #(process-expression env %) params)])))

(defmethod process-expression :unsafe-expression
  [env [_kw {:keys [operator params] :or {operator :and}}]]
  (process-unsafe-expression env [operator params]))

(defmethod process-expression :join-filter
  [env [_kw {:keys [join-key expression]}]]
  (let [subquery (-> env :join-filter-subqueries join-key)]
    (assert subquery
      (str "No join filter found for join key " join-key))
    (inline-params env
      {:raw-string subquery
       :params     [(process-expression env expression)]})))

(defmethod process-expression :join-filters
  [env [_kw join-filters]]
  (inline-params env
    {:raw-string (str "("
                   (clojure.string/join ") AND ("
                     (repeat (count join-filters) \?))
                   ")")
     :params     (mapv #(process-expression env %) join-filters)}))

(defmethod process-expression :atomic-variable
  [{::keys [formulas variable-values] :as env} [_kw atomic-variable]]
  (let [n (:name atomic-variable)]
    (if-let [formula (get formulas n)]
      formula
      (if-let [value (get variable-values n)]
        (single-raw-string value)
        (single-raw-string atomic-variable)))))

(defmethod process-expression :nil
  [_env [_kw number]]
  conformed-nil)

(defmethod process-expression :number
  [_env [_kw number]]
  {:raw-string (str number)
   :params     []})

(defmethod process-expression :boolean
  [_env [_kw value]]
  (if value
    conformed-true
    conformed-false))

(defmethod process-expression :string
  [_env [_kw string]]
  (single-raw-string string))

(defmethod process-expression :column
  [{:keys [true-columns] :as env} [_kw column-keyword]]
  (if-let [column (get true-columns column-keyword)]
    {:raw-string column
     :params     []}
    ;; non-static columns are converted to symbolic expressions
    (single-raw-string (AtomicVariable. column-keyword))))

(defmethod operator? :case [_operator] true)

(defmethod process-operator :case
  [_env [_kw expressions]]
  (let [n (count expressions)]
    (assert (> n 2)
      "`case` must have at least three arguments")
    (let [when+else-count (dec n)
          else?           (odd? when+else-count)
          when-count      (if else? (dec when+else-count) when+else-count)]
      {:raw-string (str "CASE (?)"
                     (apply str (repeat (/ when-count 2) " WHEN (?) THEN (?)"))
                     (when else? " ELSE (?)")
                     " END")
       :params     expressions})))

(defmethod operator? :cond [_operator] true)

(defmethod process-operator :cond
  [_env [_kw expressions]]
  (let [n (count expressions)]
    (assert (even? n)
      "`cond` requires an even number of arguments")
    (assert (not= n 0)
      "`cond` must have at least two arguments")
    (process-expression {} [:boolean true])
    {:raw-string (str "CASE"
                   (apply str (repeat (/ n 2) " WHEN (?) THEN (?)"))
                   " END")
     :params     expressions}))

(defmethod operator? :if [_operator] true)

(defmethod process-operator :if
  [_env [_kw expressions]]
  (let [n (count expressions)]
    (assert (#{2 3} n)
      "`if` must have either two or three arguments")
    (let [else?           (= 3 n)]
      {:raw-string (str "CASE WHEN (?) THEN (?)"
                     (when else? " ELSE (?)")
                     " END")
       :params     expressions})))

(defmethod operator? :when [_operator] true)

(defmethod process-operator :when
  [_env [_kw expressions]]
  (let [n (count expressions)]
    (assert (= 2 n)
      "`when` must have exactly two arguments")
    (let [else?           (= 3 n)]
      {:raw-string "CASE WHEN (?) THEN (?) END"
       :params     expressions})))
#_
(defn inline-atomic-variables
  [{::keys [variable-values] :as env} {:keys [raw-string params]}]
  (inline-params env
    {:raw-string raw-string
     :params     (->> params
                   (mapv #(single-raw-string
                            (get variable-values % %))))}))

(defn inline-params
  [env {:keys [raw-string params]}]
  {:params     (into [] (flatten (map :params params)))
   :raw-string (->> (conj (mapv :raw-string params) nil)
                 (interleave (if (= "?" raw-string)
                               ["" ""]
                               (string/split raw-string #"\?")))
                 (apply str))})

(defn parameterize
  [env clauses]
  (let [form (s/conform ::expression clauses)]
    (assert (not= ::s/invalid form)
      (str "Invalid expression: " clauses))
    ;;(println "clauses:" clauses)
    ;;(println "form: " form)
    (process-expression env form)))
