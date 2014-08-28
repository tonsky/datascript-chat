(ns datascript-chat.util
  (:require
    [datascript :as d]))

(defn map-by-to [key-fn val-fn xs]
  (reduce #(assoc %1 (key-fn %2) (val-fn %2)) {} xs))

(defn map-by [key-fn xs]
  (reduce #(assoc %1 (key-fn %2) %2) {} xs))

;; DATASCRIPT

(defn -q [q & args]
  (let [;;key (str q)
;;         _ (.time js/console key)
        res (apply d/q q args)]
;;     (.timeEnd js/console key)
    res))

(defn q1 [q & args]
  (->> (apply -q q args) ffirst))

(defn q1-by
  ([db attr]
    (->> (-q '[:find ?e :in $ ?a :where [?e ?a]] db attr) ffirst))
  ([db attr value]
    (->> (-q '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value) ffirst)))

(defn q1s [q & args]
  (->> (apply -q q args) (map first)))

(defn qe [q db & sources]
  (->> (apply -q q db sources)
       ffirst
       (d/entity db)))

(defn qes [q db & sources]
  (->> (apply -q q db sources)
       (map #(d/entity db (first %)))))

(defn qe-by
  ([db attr]
    (qe '[:find ?e :in $ ?a :where [?e ?a]] db attr))
  ([db attr value]
    (qe '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value)))

(defn qes-by
  ([db attr]
    (qes '[:find ?e :in $ ?a :where [?e ?a]] db attr))
  ([db attr value]
    (qes '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value)))

(defn qmap [q & sources]
  (into {} (apply -q q sources)))
