(defproject datascript-chat "0.1.0-SNAPSHOT"
  :dependencies [
    [org.clojure/clojure "1.6.0"]
    [org.clojure/clojurescript "0.0-2342"]
    [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
    [datascript "0.4.1"]
    [sablono "0.2.22"]
    [com.facebook/react "0.11.2"]
  ]
  :plugins [
    [lein-cljsbuild "1.0.3"]
  ]
  :cljsbuild { 
    :builds [
      { :id "dev"
        :source-paths ["src"]
        :compiler {
          :output-to     "web/datascript-chat.js"
          :output-dir    "web/out"
          :optimizations :none
          :source-map    true
        }}
      { :id "prod"
        :source-paths ["src"]
        :compiler {
          :externs  ["react/externs/react.js" "datascript/externs.js"]
          :preamble ["react/react.min.js"]
          :output-to     "datascript-chat.min.js"
          :optimizations :advanced
          :pretty-print  false
        }}
  ]}
)
