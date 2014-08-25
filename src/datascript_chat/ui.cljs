(ns datascript-chat.ui
  (:require
    [clojure.string :as str]
    [cljs.core.async :as async]
    [datascript :as d]
    [datascript-chat.util :as u]
    [quiescent :as q :include-macros true]
    [sablono.core :as html :refer-macros [html]] 
    [goog.string]
    [goog.string.format] ))

;; EMOJI decoder

;; (defn re-ranges [s pattern]
;;   (let [re (js/RegExp. pattern "g")]
;;     (loop [acc []]
;;       (if-let [match (.exec re s)]
;;         (recur (conj acc {:match (nth match 0)
;;                           :end   (.-lastIndex re)
;;                           :start (- (.-lastIndex re) (count (nth match 0)))}))
;;         acc))))

;; (defn chars->utf32 [chars]
;;   (let [leading  (.charCodeAt chars 0)
;;         trailing (.charCodeAt chars 1)]
;;     (->
;;       (bit-or
;;         (bit-shift-left (bit-and leading 1023) 10)
;;         (bit-and trailing 1023))
;;       (+ 65536)
;;       (.toString 16))))

;; (let [s "kidney 🐶💕 abc"
;;       re #"[\uD800-\uDBFF][\uDC00-\uDFFF]"]
;;   (str/replace s re #(str "https://abs.twimg.com/emoji/v1/72x72/" (chars->utf32 %) ".png")))


;; UTILS

(def conn (atom nil))

(defn- format-time [date]
  (goog.string/format "%02d:%02d" (.getHours date) (.getMinutes date)))

(defn- should-scroll? [node]
  (<= 
    (- (.-scrollHeight node) (.-scrollTop node) (.-offsetHeight node))
    0))

(defn node []
  (.getDOMNode q/*component*))

(defn remember [k v]
  (aset q/*component* (str k) v))

(defn recall [k]
  (aget q/*component* (str k)))

(defn- stick-to-bottom [component]
  (q/wrapper component
    :onWillUpdate (fn []
                    (remember "data-sticky" (should-scroll? (node))))
    :onUpdate     (fn [node]
                    (when (recall "data-sticky")
                      (set! (.-scrollTop node) (.-scrollHeight node))))))


;; COMMUNICATION

(defn- select-room [chan rid]
  (async/put! chan [:select-room rid]))

(defn- send-msg [chan text]
  (async/put! chan [:send-msg text]))


;; UI COMPONENTS

(q/defcomponent Room [[room user last-msg unread] event-bus]
  (html
    [:.topic {:class (when (:room/selected room) "topic_selected")
                 :on-click (fn [_]
                             (select-room event-bus (:db/id room))
                             (.focus (.getElementById js/document "compose__area")))}
      (if last-msg
        (list
          [:img.topic__avatar {:src (:user/avatar user)}]
          [:.topic__title (:room/title room)
            (when (pos? unread)
              [:span.topic__unread (str unread)])]
          [:.topic__msg (:message/text last-msg)]
          [:.topic__ts
            (:user/name user)
            " at "
            (format-time (:message/timestamp last-msg))
            ])
        [:.topic__title (:room/title room)])]))

(q/defcomponent Avatar [user]
;;   (q/wrapper
    (html
      [:.message__avatar
        [:img {:src (:user/avatar user "avatars/loading.jpg")}]])
;;     :onMount       (fn [node]
;;                      (let [cmp q/*component*
;;                            key (d/listen! @conn #(.forceUpdate cmp))]
;;                        (remember "data-listen-key" key)))
;;     :onWillUnmount (fn []
;;                      (d/unlisten! @conn (recall "data-listen-key"))))
  )

(q/defcomponent RoomsPane [db event-bus]
  (let [rooms         (map u/entity->map (u/qes-by db :room/title))
        last-msgs     (->> (d/q '[:find  ?r (max ?m)
                                  :where [?m :message/room ?r]]
                                db)
                           (map (fn [[rid msgid]] [rid (d/entity db msgid)]))
                           (into {}))
        unread-counts (->> (d/q '[:find ?r (count ?m)
                                  :where [?m :message/unread]
                                         [?m :message/room ?r]]
                                db)
                           (into {}))]
    (html
      [:#rooms__pane.pane
        [:#rooms__header.header
          [:.title "Rooms"]]
        (map #(let [rid      (:db/id %)
                    last-msg (get last-msgs rid)]
                (Room [%
                       (u/entity->map (:message/author last-msg))
                       last-msg
                       (get unread-counts rid)]
                      event-bus))
             (sort-by :room/title rooms))])))

(q/defcomponent Text [text]
  (html
    [:.message__text
      (map #(vector :p %) (str/split-lines text))]))

(q/defcomponent Message [[msg user]]
  (html
    [:.message {:class [(when (:message/unread msg) "message_unread")
                        (when (:user/me user)       "me")]}
      (Avatar user)
      [:.message__name
        (:user/name user)
        [:span.message__ts
          (format-time (:message/timestamp msg))]]
      (Text (:message/text msg))]))
     
(q/defcomponent ChatPane [[room messages _]]
  (stick-to-bottom
    (html
      [:#chat__pane.pane
        [:#chat__header.header
          [:.title (:room/title room "Loading...")]]
        (let [msgs (sort-by :message/timestamp messages)]
          (map #(Message [% (u/entity->map (:message/author %))])
               msgs))])))

(defn- textarea-keydown [callback]
  (fn [e]
    (if (and (== (.-keyCode e) 13) ;; enter
             (not (.-shiftKey e))) ;; no shift
      (do
        (callback (.. e -target -value))
        (set! (.. e -target -value) "")
        (.preventDefault e)))))

(q/defcomponent ComposePane [user event-bus]
  (html
    [:#compose
      (Avatar user)
      [:textarea#compose__area.message__text
        { :placeholder "Reply..."
          :auto-focus  true
          :on-key-down  (textarea-keydown #(send-msg event-bus %)) }]]))

(q/defcomponent Window [db event-bus]
  (let [selected (u/qe-by  db :room/selected true)
        messages (u/qes-by db :message/room (:db/id selected))
        me       (u/qe-by  db :user/me true)
        users    (->> (u/qes-by db :user/name) (u/map-by-to :db/id :system/state))]
    (html
      [:#window
        [:#rooms (RoomsPane db event-bus)]
        [:#chat  (ChatPane [selected messages users])]
        (ComposePane me event-bus)])))

(def render-queue (atom nil))

(defn request-rerender [db event-bus]
  (reset! render-queue [db event-bus]))

(defn- -render [db event-bus]
  (let [key (str "Render (" (count (:eavt db)) " datoms)")]
    (.time js/console key)
    (q/render (Window db event-bus) (.-body js/document))
    (.timeEnd js/console key)))

(defn- render []
  (when-let [args @render-queue]
    (apply -render args)
    (reset! render-queue nil)))

(add-watch render-queue :render (fn [_ _ old-val new-val]
  (when (and (nil? old-val) new-val)
    (js/requestAnimationFrame render))))
