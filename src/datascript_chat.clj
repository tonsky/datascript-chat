(ns datascript-chat
  (:require
    [cljs.core.async.macros :refer [go]]))

(defmacro go-sub [[binding [pub key]] & body]
 `(let [ch# (cljs.core.async/chan)]
    (cljs.core.async/sub ~pub ~key ch#)
    (go
      (loop []
        (let [~binding (cljs.core.async/<! ch#)]
          ~@body)
        (recur)))))
