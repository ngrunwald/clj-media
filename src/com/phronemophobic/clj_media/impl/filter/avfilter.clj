(ns com.phronemophobic.clj-media.impl.filter.avfilter
  (:require [com.phronemophobic.clj-media.impl.filter.frame
             :as ff]
            [com.phronemophobic.clj-media.impl.filter.media
             :as fm]
            [clojure.string :as str]
            [clojure.core.protocols :as p]
            [clojure.datafy :as d]
            [net.cgrand.xforms :as x]
            [clojure.java.io :as io]
            [com.phronemophobic.clj-media.av :as av]
            [com.phronemophobic.clj-media.audio :as audio]
            [com.phronemophobic.clj-media.video :as video]
            [com.phronemophobic.clj-media.impl.util
             :refer [distinct-by]]
            [com.phronemophobic.clj-media.av.raw :as raw
             :refer :all])
  (:import
   java.io.PushbackReader
   java.nio.ByteOrder
   java.nio.ByteBuffer
   com.sun.jna.Memory
   com.sun.jna.Pointer
   com.sun.jna.ptr.PointerByReference
   com.sun.jna.ptr.IntByReference
   com.sun.jna.ptr.LongByReference
   com.sun.jna.ptr.DoubleByReference
   com.sun.jna.ptr.ByteByReference
   java.lang.ref.Cleaner
   com.sun.jna.Structure))

(raw/import-structs!)

(def avoption-type->kw
  (->> (:enums raw/av-api)
       (filter (fn [enum]
                 (= "AVOptionType" (:enum enum))))
       (map (juxt :value
                  (fn [enum]
                    (keyword "avoption-type"
                             (-> (subs (:name enum)
                                       (count "AV_OPT_TYPE_"))
                                 str/lower-case
                                 (str/replace #"_" "-"))))))
       (into {})))

(defmulti read-bytes (fn [type bs]
                       type))
(defmethod read-bytes :avoption-type/int64
  [_ bs]
  (let [_ (assert (= 8 (alength bs)))
        bs (if (= (ByteOrder/nativeOrder)
                  ByteOrder/LITTLE_ENDIAN)
             (byte-array (reverse bs))
             bs)
        val (BigInteger. bs)]
    val))

(defmethod read-bytes :avoption-type/int
  [_ bs]
  (-> (ByteBuffer/wrap bs)
      (.order (ByteOrder/nativeOrder))
      (.getInt)))

(defmethod read-bytes :avoption-type/bool
  [_ bs]
  (let [num (read-bytes :avoption-type/uint64 bs)]
    (not (zero? num))))

(defmethod read-bytes :avoption-type/uint64
  [_ bs]
  (let [_ (assert (= 8 (alength bs)))
        bs (if (= (ByteOrder/nativeOrder)
                  ByteOrder/LITTLE_ENDIAN)
             (byte-array (reverse bs))
             bs)
        val (BigInteger. 1 bs)]
    val))

(defmethod read-bytes :avoption-type/double
  [_ bs]
  (-> (ByteBuffer/wrap bs)
      (.order (ByteOrder/nativeOrder))
      (.getDouble)))

(defmethod read-bytes :avoption-type/float
  [_ bs]
  (-> (ByteBuffer/wrap bs)
      (.order (ByteOrder/nativeOrder))
      (.getFloat)))

(defmethod read-bytes :avoption-type/string
  [_ bs]
  (let [ptr-native (read-bytes :avoption-type/int64 bs )]
    (when (not (zero? ptr-native))
      (let [p (Pointer.  ptr-native)]
        (.getString p 0 "ascii")))))

(defmethod read-bytes :default
  [type bs]
  {:type type
   :bs bs
   :float (read-bytes :avoption-type/float bs)
   :double (read-bytes :avoption-type/double bs)
   :long (read-bytes :avoption-type/int64 bs)
   :int (read-bytes :avoption-type/int bs)})

(extend-protocol p/Datafiable
  AVOptionByReference
  (datafy [opt]
    (let [option-type (avoption-type->kw (:type opt))]
      (merge
       {:name (.getString (.getPointer (:name opt)) 0 "ascii")
        :offset (:offset opt)
        :type option-type}
       (when-let [help (:help opt)]
         {:help (.getString (.getPointer help) 0 "ascii")})
       (when-let [default (:default_val opt)]
         {:default-val (read-bytes option-type default)})
       (when-let [min (:min opt)]
         {:min min})
       (when-let [max (:max opt)]
         {:max max})
       (when-let [unit (:unit opt)]
         {:unit (.getString (.getPointer unit) 0 "ascii")})))))

(defn filter-options [flt]
  (let [cls (:priv_class flt)]
    (when cls
      (let [cls* (PointerByReference. (.getPointer cls))]
        (loop [prev nil
               opts []]
          (let [o (av_opt_next cls* prev)]
            (if o
              (recur o (conj opts (d/datafy o)))
              opts)))))))



(extend-protocol p/Datafiable
  AVFilterByReference
  (datafy [flt]
    (merge
     {:name (.getString (.getPointer (:name flt)) 0 "ascii")
      :options (filter-options flt)}
     (when-let [description (.getPointer (:description flt))]
       {:description (.getString description 0 "ascii")})
     (when-let [inputs (:inputs flt)]
       (let [size (avfilter_filter_pad_count flt 0)]
         {:inputs
          (into []
                (map (fn [i]
                       (let [name (avfilter_pad_get_name inputs i)
                             type (avfilter_pad_get_type inputs i)]
                         {:media-type
                          (condp = type
                            AVMEDIA_TYPE_AUDIO :media-type/audio
                            AVMEDIA_TYPE_VIDEO :media-type/video)
                          :name name})))
                (range size))}))
     (when-let [outputs (:outputs flt)]
       (let [size (avfilter_filter_pad_count flt 1)]
         {:outputs
          (into []
                (map (fn [i]
                       (let [name (avfilter_pad_get_name outputs i)
                             type (avfilter_pad_get_type outputs i)]
                         {:media-type
                          (condp = type
                            AVMEDIA_TYPE_AUDIO :media-type/audio
                            AVMEDIA_TYPE_VIDEO :media-type/video)
                          :name name})))
                (range size))})))))


(defn list-filters []
  (let [iter-data (PointerByReference. Pointer/NULL)]
    (loop [flts []]
      (let [flt (av_filter_iterate iter-data)]
        (if flt
          (recur (conj flts (d/datafy flt)))
          flts)))))


(defn print-options [obj]
  (loop [prev nil]
    (let [opt (av_opt_next obj prev)]
      (when opt
        (clojure.pprint/pprint
         (let [option-type (avoption-type->kw (:type opt))
               name (.getString (.getPointer (:name opt)) 0 "ascii")
               val
               (case option-type
                 (:avoption-type/int64
                  :avoption-type/int)
                 (let [num* (LongByReference.)]
                   (av_opt_get_int obj (:name opt) AV_OPT_SEARCH_CHILDREN num*)
                   (.getValue num*))

                 (:avoption-type/double
                  :avoption-type/float)
                 (let [num* (DoubleByReference.)]
                   (av_opt_get_double obj (:name opt) AV_OPT_SEARCH_CHILDREN num*)
                   (.getValue num*))

                 ;; else
                 (let [out (ByteByReference.)]
                   (av_opt_get obj (:name opt) AV_OPT_SEARCH_CHILDREN out)
                   (.getValue out)))]
          {:name name
           :type option-type
           :val val})))
      )))

(defn edge-detect [input-format opts]
  (fn [rf]
    (let [

          filter-graph (avfilter_graph_alloc)

          buffer (avfilter_get_by_name "buffer")
          _ (when (nil? buffer)
              (throw (Exception.)))
          buffer-context (avfilter_graph_alloc_filter filter-graph buffer "src")
          time-base (:time-base input-format)
          args (format "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d"
                       (:width input-format)
                       (:height input-format)
                       (:pix-fmt input-format)
                       (:num time-base)
                       (:den time-base))

          err (avfilter_init_str buffer-context args)
          _ (when (not (zero? err))
              (throw (Exception.)))

          buffersink (avfilter_get_by_name "buffersink")
          _ (when (nil? buffersink)
              (throw (Exception.)))
          buffersink-context* (PointerByReference.)
          _ (avfilter_graph_create_filter buffersink-context*
                                          buffersink
                                          nil
                                          nil
                                          nil
                                          filter-graph)

          buffersink-context (.getValue buffersink-context*)

          pix-fmts (doto (IntByReference.)
                     (.setValue (:pix-fmt input-format)))
          _ (av_opt_set_bin buffersink-context "pix_fmts"
                            pix-fmts
                            (* 1 4)
                            AV_OPT_SEARCH_CHILDREN)

          ;; create the edge detecting filter
          edgedetect-filter (avfilter_graph_alloc_filter
                             filter-graph
                             (avfilter_get_by_name "edgedetect")
                             nil)
          _ (assert edgedetect-filter)
          _ (av_opt_set_int edgedetect-filter
                            "mode"
                            2
                            AV_OPT_SEARCH_CHILDREN)
          _ (avfilter_init_str edgedetect-filter nil)
          _ (print-options edgedetect-filter)

          err (avfilter_link buffer-context 0
                             edgedetect-filter 0)
          _ (when (not (zero? err))
              (throw (Exception.)))
          err (avfilter_link edgedetect-filter 0
                             buffersink-context 0)
          _ (when (not (zero? err))
              (throw (Exception.)))

          err (avfilter_graph_config filter-graph nil)

          filter-graph* (doto (PointerByReference.)
                          (.setValue (.getPointer filter-graph)))]
      (.register av/cleaner filter-graph
                 (fn []
                   (avfilter_graph_free filter-graph*)))

      (when (not (>= err 0))
        (throw (Exception.)))
      (fn
        ([]
         (rf))
        ([result]
         (rf result))
        ([result input-frame]
         (av_buffersrc_add_frame buffer-context
                                 input-frame)
         (loop [result result]
           (let [frame (av/new-frame)
                 err (av_buffersink_get_frame_flags buffersink-context
                                                    frame
                                                    0)]
             (cond
               (zero? err)
               (let [result (rf result frame) ]
                 (if (reduced? result)
                   result
                   (recur result)))

               (av/eagain? err)
               result

               (av/eof? err)
               (reduced result)

               :else
               (reduced {:error-code err
                         :error-msg (av/error->str err)
                         :type :transcode-error}))))))))
  )



(defrecord EdgeDetect [opts media]
  fm/IMediaSource
  (-media [this]
    (mapv (fn [src]
            (let [input-format (fm/-format src)]
              (case (:media-type input-format)
                :media-type/audio src

                :media-type/video
                (let []
                  (fm/->FrameSource (sequence
                                     (edge-detect input-format opts)
                                     (fm/-frames src))

                                    input-format)))))
          (fm/-media media))))

(defn str->kw [s]
  (-> s
      str/lower-case
      (str/replace #"_" "-")
      keyword))

(defn str->symbol [s]
  (-> s
      str/lower-case
      (str/replace #"_" "-")
      symbol))

(defmulti set-option (fn [o type k v]
                       type))



#_(defmethod set-option :avoption-type/flags
  [o _ k v])
(defmethod set-option :avoption-type/float
  [o _ k v]
  (av_opt_set_double o k v AV_OPT_SEARCH_CHILDREN))
(defmethod set-option :avoption-type/rational
  [o _ k v]
  (when-not (ratio? v)
    (throw (ex-info "Option type :avoption-type/rational must be set with Ratio."
                    {:o o
                     :k k
                     :v v})))
  (av_opt_set_q o k
                (av/->avrational (numerator v)
                                 (denominator v))
                AV_OPT_SEARCH_CHILDREN))
#_(defmethod set-option :avoption-type/duration
  [o _ k v])
(defmethod set-option :avoption-type/int64
  [o _ k v]
  (av_opt_set_int o k v AV_OPT_SEARCH_CHILDREN))
(defmethod set-option :avoption-type/double
  [o _ k v]
  (av_opt_set_double o k v AV_OPT_SEARCH_CHILDREN))
(defmethod set-option :avoption-type/int
  [o _ k v]
  (av_opt_set_int o k v AV_OPT_SEARCH_CHILDREN))
#_(defmethod set-option :avoption-type/dict
  [o _ k v])
(defmethod set-option :avoption-type/image-size
  [o _ k v]
  (let [[w h] v]
   (av_opt_set_image_size o k w h AV_OPT_SEARCH_CHILDREN)))
#_(defmethod set-option :avoption-type/video-rate
  [o _ k v])
(defmethod set-option :avoption-type/string
  [o _ k v]
  (av_opt_set o k v AV_OPT_SEARCH_CHILDREN))
#_(defmethod set-option :avoption-type/const
  [o _ k v])
(defmethod set-option :avoption-type/sample-fmt
  [o _ k v]
  (let [kw->sample-format
        (into {}
              (map (fn [[k v]]
                     [v k]))
              av/sample-format->kw)
        fmt (or
             (get kw->sample-format v)
             v)]
    (when-not (contains? av/sample-format->kw fmt)
      (throw (ex-info "Invalid sample format."
                      {:o o
                       :k k
                       :v v})))
    (av_opt_set_sample_fmt o k fmt AV_OPT_SEARCH_CHILDREN)))
(defmethod set-option :avoption-type/pixel-fmt
  [o _ k v]
  (let [kw->pixel-format
        (into {}
              (map (fn [[k v]]
                     [v k]))
              av/pixel-format->kw)
        pix-fmt (or
                 (get kw->pixel-format v)
                 v)]
    (when-not (contains? av/pixel-format->kw pix-fmt)
      (throw (ex-info "Invalid pixel format."
                      {:o o
                       :k k
                       :v v})))
    (av_opt_set_pixel_fmt o k pix-fmt AV_OPT_SEARCH_CHILDREN)))
#_(defmethod set-option :avoption-type/binary
  [o _ k v])
#_(defmethod set-option :avoption-type/color
  [o _ k v])
(defmethod set-option :avoption-type/bool
  [o _ k v]
  (av_opt_set_int o k (if v 1 0) AV_OPT_SEARCH_CHILDREN))


(defmulti set-filter-option
  (fn [obj filter-name k v]
    [filter-name k]))

(defn set-filter-context-options [filter-context filter-name opts]
  (doseq [[k v] opts]
    (set-filter-option filter-context filter-name k v)))

(defn supported-filter-options [options]
  (eduction
   (filter (fn [option]
             (get-method set-option (:type option))))
   options))

(defn filter-setters [filter-info]
  (let [filter-name (:name filter-info)
        options (:options filter-info)
        consts (->> options
                    (filter #(= (:type %)
                                :avoption-type/const))
                    (group-by :unit))]
    `(do
       ~@(eduction
          (map (fn [option]
                 (let [s (:name option)
                       k (str->kw s)
                       v## (gensym "v")]
                   `(defmethod set-filter-option [~filter-name ~k]
                      [obj# _filter-name# _k# ~v##]
                      (let [~v## ~(if-let [const-options (get consts s)]
                                    ;; assumes int type
                                    ;; ignore :avoption-type/flags and :avoption-type/const
                                    (let [m (into {}
                                                  (map (fn [opt]
                                                         [(-> opt
                                                              :name)
                                                          (-> opt
                                                              :default-val
                                                              :int)]))
                                                  const-options)] 
                                      `(get ~m ~v## ~v##))
                                    v##)]
                        (set-option obj# ~(:type option) ~s ~v##))))))
          (supported-filter-options options)))))

(defmacro make-setters []
  `(do
     ~@(mapv filter-setters (list-filters))))

(make-setters)


(defn video-filter [input-format filter-name opts]
  (fn [rf]
    (let [filter-graph (avfilter_graph_alloc)

          buffer (avfilter_get_by_name "buffer")
          _ (when (nil? buffer)
              (throw (Exception.)))
          buffer-context (avfilter_graph_alloc_filter filter-graph buffer "src")
          time-base (:time-base input-format)
          args (format "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d"
                       (:width input-format)
                       (:height input-format)
                       (:pix-fmt input-format)
                       (:num time-base)
                       (:den time-base))

          err (avfilter_init_str buffer-context args)
          _ (when (not (zero? err))
              (throw (Exception.)))

          buffersink (avfilter_get_by_name "buffersink")
          _ (when (nil? buffersink)
              (throw (Exception.)))
          buffersink-context* (PointerByReference.)
          _ (avfilter_graph_create_filter buffersink-context*
                                          buffersink
                                          nil
                                          nil
                                          nil
                                          filter-graph)

          buffersink-context (.getValue buffersink-context*)

          pix-fmts (doto (IntByReference.)
                     (.setValue (:pix-fmt input-format)))
          _ (av_opt_set_bin buffersink-context "pix_fmts"
                            pix-fmts
                            (* 1 4)
                            AV_OPT_SEARCH_CHILDREN)

          ;; create the filter
          filter-context (avfilter_graph_alloc_filter
                          filter-graph
                          (avfilter_get_by_name filter-name)
                          nil)
          _ (assert filter-context)
          _ (set-filter-context-options filter-context filter-name opts)

          _ (avfilter_init_str filter-context nil)

          err (avfilter_link buffer-context 0
                             filter-context 0)
          _ (when (not (zero? err))
              (throw (Exception.)))
          err (avfilter_link filter-context 0
                             buffersink-context 0)
          _ (when (not (zero? err))
              (throw (Exception.)))

          err (avfilter_graph_config filter-graph nil)

          filter-graph* (doto (PointerByReference.)
                          (.setValue (.getPointer filter-graph)))
          filter-graph-ref (volatile! filter-graph)]
      (.register av/cleaner filter-graph
                 (fn []
                   (avfilter_graph_free filter-graph*)))
      (.register av/cleaner buffersink-context
                 (fn []
                   (vreset! filter-graph-ref nil)))
      (when (not (>= err 0))
        (throw (Exception.)))
      (fn
        ([]
         (rf))
        ([result]
         (rf result))
        ([result input-frame]
         (av_buffersrc_add_frame buffer-context
                                 input-frame)
         (loop [result result]
           (let [frame (av/new-frame)
                 err (av_buffersink_get_frame_flags buffersink-context
                                                    frame
                                                    0)]
             (cond
               (zero? err)
               (let [result (rf result frame) ]
                 (if (reduced? result)
                   result
                   (recur result)))

               (av/eagain? err)
               result

               (av/eof? err)
               (reduced result)

               :else
               (reduced {:error-code err
                         :error-msg (av/error->str err)
                         :type :transcode-error}))))))))
  )

(defn audio-filter [input-format filter-name opts]
  (fn [rf]
    (let [

          filter-graph (avfilter_graph_alloc)

          buffer (avfilter_get_by_name "abuffer")
          _ (when (nil? buffer)
              (throw (Exception.)))
          buffer-context (avfilter_graph_alloc_filter filter-graph buffer nil)

          args (format "channel_layout=%s:sample_fmt=%d:sample_rate=%d"
                       (av/ch-layout->str
                        (:ch-layout input-format ))
                       (:sample-format input-format)
                       (:sample-rate input-format))
          _ (prn args)
          err (avfilter_init_str buffer-context args)
          _ (when (not (zero? err))
              (throw (Exception.)))

          buffersink (avfilter_get_by_name "abuffersink")
          _ (when (nil? buffersink)
              (throw (Exception.)))
          buffersink-context* (PointerByReference.)
          _ (avfilter_graph_create_filter buffersink-context*
                                          buffersink
                                          nil
                                          nil
                                          nil
                                          filter-graph)

          buffersink-context (.getValue buffersink-context*)

          sample-fmts (doto (IntByReference.)
                        (.setValue (:sample-format input-format)))
          _ (av_opt_set_bin buffersink-context "sample_fmts"
                            sample-fmts
                            (* 1 4)
                            AV_OPT_SEARCH_CHILDREN)

          ;; create the filter
          filter-context (avfilter_graph_alloc_filter
                          filter-graph
                          (avfilter_get_by_name filter-name)
                          nil)
          _ (assert filter-context)
          _ (set-filter-context-options filter-context filter-name opts)

          _ (avfilter_init_str filter-context nil)

          err (avfilter_link buffer-context 0
                             filter-context 0)
          _ (when (not (zero? err))
              (throw (Exception.)))
          err (avfilter_link filter-context 0
                             buffersink-context 0)
          _ (when (not (zero? err))
              (throw (Exception.)))

          err (avfilter_graph_config filter-graph nil)

          filter-graph* (doto (PointerByReference.)
                          (.setValue (.getPointer filter-graph)))
          filter-graph-ref (volatile! filter-graph)]
      (.register av/cleaner filter-graph
                 (fn []
                   (avfilter_graph_free filter-graph*)))
      (.register av/cleaner buffersink-context
                 (fn []
                   (vreset! filter-graph-ref nil)))
      (when (not (>= err 0))
        (throw (Exception.)))
      (fn
        ([]
         (rf))
        ([result]
         (rf result))
        ([result input-frame]
         (av_buffersrc_add_frame buffer-context
                                 input-frame)
         (loop [result result]
           (let [frame (av/new-frame)
                 err (av_buffersink_get_frame_flags buffersink-context
                                                    frame
                                                    0)]
             (cond
               (zero? err)
               (let [result (rf result frame) ]
                 (if (reduced? result)
                   result
                   (recur result)))

               (av/eagain? err)
               result

               (av/eof? err)
               (reduced result)

               :else
               (reduced {:error-code err
                         :error-msg (av/error->str err)
                         :type :transcode-error})))))))))


(defrecord AVFilterMedia [filter-name opts media-types media]
  fm/IMediaSource
  (-media [this]
    (fm/-media this media))
  (-media [this media]
    (mapv (fn [src]
            (let [input-format (fm/-format src)
                  media-type (:media-type input-format)]

              (if (media-types media-type)
                (let [f (case media-type
                          :media-type/audio audio-filter
                          :media-type/video video-filter)
                      frames (sequence (f input-format filter-name opts)
                                       (fm/-frames src))
                      first-frame (first frames)
                      output-format
                      (case media-type
                        :media-type/audio
                        (assoc input-format
                               :ch-layout (:ch_layout first-frame)
                               :sample-format (:format first-frame)
                               :sample-rate (:sample_rate first-frame))
                        :media-type/video
                        (assoc input-format
                               :width (:width first-frame)
                               :height (:height first-frame)
                               :pix-fmt (:format first-frame)))]
                  (fm/->FrameSource
                   frames
                   output-format))
                ;; else, don't change
                src)))
          (fm/-media media)))
  )


(defn filter-fn [filter-info]
  (let [opts## 'opts
        opts-or-media## 'opts-or-media
        media## 'media

        filter-name (:name filter-info)
        fn-name (str->symbol filter-name)

        supported-options (supported-filter-options (:options filter-info))
        opt-keys (into []
                       (comp (map :name)
                             (map str->symbol))
                       supported-options)

        opts {:keys opt-keys
              :as opts##}

        consts (->> (:options filter-info)
                    (filter #(= (:type %)
                                :avoption-type/const))
                    (group-by :unit))

        doc-string
        (str filter-name ": " (:description filter-info)
             "\n\n"
             "Supported options:

"
             (clojure.string/join
              "\n\n"
              (eduction
               (map (fn [{:keys [name type help default-val min max]}]
                      (str (str->kw name) " - " help
                           "\n"
                           (clojure.string/join
                            "\n"
                            (eduction
                             (remove nil?)
                             (map #(str "\t" %))
                             (if-let [const (get consts name)]
                               [(str "type: enum" )
                                (str "default: " (some (fn [c]
                                                         (when (= (-> c :default-val :int)
                                                                  default-val)
                                                           (:name c)))
                                                       const))
                                (str "values: "
                                     (clojure.string/join
                                      ", "
                                      (eduction
                                       (map :name)
                                       (map #(str "\"" % "\""))
                                       const)))
                                ]
                               [(str "type: " (clojure.core/name type))
                                (when (not (map? default-val))
                                  (str "default: " default-val))
                                (str "min: " min)
                                (str "max: " max)]))
                            )
                           )))
               (sort-by :name supported-options))))

        media-types (-> filter-info
                        :inputs
                        first
                        :media-type
                        hash-set)]
    `(defn ~fn-name
       ~doc-string
       ([]
        (~fn-name nil nil))
       ([~opts-or-media##]
        (let [media?# (fm/media-source? ~opts-or-media##)
              opts# (if media?#
                      nil
                      ~opts-or-media##)
              media# (if media?#
                       ~opts-or-media##
                       nil)]
          (~fn-name opts# media#)))
       ([~opts ~media##]
        (->AVFilterMedia ~filter-name ~opts## ~media-types ~media##)))))


(defn supported-filter-type? [filter-info]
  (println (= 1 (count (:inputs filter-info)))
       (= 1 (count (:outputs filter-info)))
       (= (:media-type (first (:outputs filter-info)))
          (:media-type (first (:inputs filter-info)))))
  (and (= 1 (count (:inputs filter-info)))
       (= 1 (count (:outputs filter-info)))
       (= (:media-type (first (:outputs filter-info)))
          (:media-type (first (:inputs filter-info))))))

(defmacro make-fns []
  `(do
     ~@(into []
             (comp (filter supported-filter-type?)
                   (map filter-fn))
             (list-filters))))


(defn ^:private ->url-str [fname]
  (str "file://" (.getCanonicalPath (io/file fname))))

(defn -main [& args]
  (let [outname (or (first args)
                    "test.mov")
        default-input (->url-str "/Users/adrian/workspace/eddie/vids/guitar_notes.mp4")
        [inname outname]
        (case (count args)
          0 [default-input
             "test.mp4"]
          1 [default-input
             (first args)]

          ;; else
          [(str "file://" (.getCanonicalPath (io/file (first args))))
           (second args)]
          )]
    (fm/write! (-> (fm/->MediaFile
                 inname)

                   (->> (->AVFilterMedia "aecho" {} #{:media-type/audio})
                        (->AVFilterMedia "aecho" {} #{:media-type/audio})
                        (->AVFilterMedia "volume" {:volume "0"} #{:media-type/audio})
                        (->AVFilterMedia "edgedetect" {:mode "canny"} #{:media-type/video})
                        (fm/->Scale 0.5 1.5))
                   )
            outname)))