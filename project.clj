(defproject datascript-chat "0.1.0-SNAPSHOT"
  :dependencies [
    [org.clojure/clojure "1.7.0"]
    [org.clojure/clojurescript "1.7.122"]
    [org.clojure/core.async "0.1.346.0-17112a-alpha"]
    [datascript "0.13.0"]
    [rum "0.4.0"]
  ]
  :plugins [
    [lein-cljsbuild "1.1.0"]
  ]
  :cljsbuild { 
    :builds [
      { :id "none"
        :source-paths ["src"]
        :compiler {
          :main          datascript-chat.core
          :output-to     "target/datascript-chat.js"
          :output-dir    "target/none"
          :optimizations :none
          :source-map    true
        }}
      { :id "advanced"
        :source-paths ["src"]
        :compiler {
          :main          datascript-chat.core
          :output-to     "target/datascript-chat.js"
          :optimizations :advanced
          :pretty-print  false
        }}
  ]}
)
