(defproject datascript-chat "0.1.0-SNAPSHOT"
  :dependencies [
    [org.clojure/clojure "1.7.0-RC1"]
    [org.clojure/clojurescript "0.0-3297"]
    [org.clojure/core.async "0.1.346.0-17112a-alpha"]
    [datascript "0.11.3"]
    [rum "0.2.6"]
  ]
  :plugins [
    [lein-cljsbuild "1.0.6"]
  ]
  :cljsbuild { 
    :builds [
      { :id "none"
        :source-paths ["src"]
        :compiler {
          :main          datascript-chat
          :output-to     "target/datascript-chat.js"
          :output-dir    "target/none"
          :optimizations :none
          :source-map    true
          :warnings      {:single-segment-namespace false}
        }}
      { :id "advanced"
        :source-paths ["src"]
        :compiler {
          :main          datascript-chat
          :output-to     "target/datascript-chat.js"
          :optimizations :advanced
          :pretty-print  false
          :warnings      {:single-segment-namespace false}
        }}
  ]}
)
