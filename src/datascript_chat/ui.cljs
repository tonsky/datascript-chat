(ns datascript-chat.ui
  (:require
    [clojure.string :as str]
    [cljs.core.async :as async]
    [datascript :as d]
    [datascript-chat.util :as u]
    [datascript-chat.react :as r :include-macros true]
    [sablono.core :as s]
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

;; (let [s "abc 🐶💕 def"
;;       re #"[\uD800-\uDBFF][\uDC00-\uDFFF]"]
;;   (str/replace s re #(str "https://abs.twimg.com/emoji/v1/72x72/" (chars->utf32 %) ".png")))


;; UTILS

(defn- format-time [date]
  (goog.string/format "%02d:%02d" (.getHours date) (.getMinutes date)))

(defn- should-scroll? [node]
  (<= 
    (- (.-scrollHeight node) (.-scrollTop node) (.-offsetHeight node))
    0))

;; COMMUNICATION

(defn- select-room [chan rid]
  (async/put! chan [:select-room rid]))

(defn- send-msg [chan text]
  (async/put! chan [:send-msg text]))


;; UI COMPONENTS

(r/defc avatar [user]
  [:.message__avatar
    [:img {:src (:user/avatar user "avatars/loading.jpg")}]])

(r/defc room [room last-msg unread event-bus]
  (let [
;;         last-msg (u/qe '[:find  (max ?m)
;;                          :in    $ ?r
;;                          :where [?m :message/room ?r]]
;;                        db (:db/id room))
;;         unread   (u/q1 '[:find (count ?m)
;;                          :in   $ ?r
;;                          :where [?m :message/room ?r]
;;                                 [?m :message/unread]]
;;                         db (:db/id room))
        user     (:message/author last-msg)]
    [:.topic { :class    (when (:room/selected room) "topic_selected")
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

(r/defc rooms-pane [db event-bus]
  (let [rooms         (->> (u/qes-by db :room/title)
                           (sort-by :room/title))
        last-msgs     (u/qmap '[:find  ?r (max ?m)
                                :where [?m :message/room ?r]]
                                db)
        unread-counts (u/qmap '[:find ?r (count ?m)
                                :where [?m :message/unread]
                                       [?m :message/room ?r]]
                                db)]
    [:#rooms__pane.pane
      [:#rooms__header.header
        [:.title "Rooms"]]
      (map #(let [rid (:db/id %)]
              (room %
                    (when-let [mid (get last-msgs rid)]
                      (d/entity db mid))
                    (get unread-counts rid)
                    event-bus))
           rooms)]))

(r/defc text [text]
  [:.message__text
    (map #(vector :p %) (str/split-lines text))])

(r/defc message [msg]
  (let [user (:message/author msg)]
    [:.message {:class [(when (:message/unread msg) "message_unread")
                        (when (:user/me user)       "me")]}
      (avatar user)
      [:.message__name
        (:user/name user)
        [:span.message__ts
          (format-time (:message/timestamp msg))]]
      (text (:message/text msg))]))
     
(r/defc chat-pane [db]
  (let [room (u/qe-by db :room/selected true)
        msgs (->> (u/qes-by db :message/room (:db/id room))
                  (sort-by :message/timestamp))]
    [:#chat__pane.pane
      [:#chat__header.header
        [:.title (:room/title room "Loading...")]]
        (map message msgs)])
  :will-update (fn [node]
                 (r/remember :sticky? (should-scroll? node)))
  :did-update  (fn [node]
                 (when (r/recall :sticky?)
                   (set! (.-scrollTop node) (.-scrollHeight node)))))

(defn- textarea-keydown [callback]
  (fn [e]
    (if (and (== (.-keyCode e) 13) ;; enter
             (not (.-shiftKey e))) ;; no shift
      (do
        (callback (.. e -target -value))
        (set! (.. e -target -value) "")
        (.preventDefault e)))))

(r/defc compose-pane [db event-bus]
  [:#compose
    (avatar (u/qe-by db :user/me true))
    [:textarea#compose__area.message__text
      { :placeholder "Reply..."
        :auto-focus  true
        :on-key-down  (textarea-keydown #(send-msg event-bus %)) }]])

(r/defc window [db event-bus]
  [:#window
    [:#rooms (rooms-pane db event-bus)]
    [:#chat  (chat-pane db)]
    (compose-pane db event-bus)])



(def render-queue (atom nil))

(defn request-rerender [db event-bus]
  (reset! render-queue [db event-bus]))

(defn- -render [db event-bus]
  (let [key (str "Render (" (count (:eavt db)) " datoms)")]
    (.time js/console key)
    (r/render (window db event-bus) (.-body js/document))
    (.timeEnd js/console key)))

(defn- render []
  (when-let [args @render-queue]
    (apply -render args)
    (reset! render-queue nil)))

(add-watch render-queue :render (fn [_ _ old-val new-val]
  (when (and (nil? old-val) new-val)
    (js/requestAnimationFrame render))))
