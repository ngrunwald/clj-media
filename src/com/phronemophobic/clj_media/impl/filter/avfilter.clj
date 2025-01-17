(ns com.phronemophobic.clj-media.impl.filter.avfilter
  (:require [com.phronemophobic.clj-media.impl.filter.frame
             :as ff]
            [com.phronemophobic.clj-media.impl.filter.media
             :as fm]
            [clojure.string :as str]
            [clojure.datafy :as d]
            [com.phronemophobic.clj-media.impl.datafy
             :refer [set-filter-context-options
                     supported-filter-option?
                     list-filters]
             :as datafy-media]
            [clojure.java.io :as io]
            [com.phronemophobic.clj-media.impl.av :as av]
            [com.phronemophobic.clj-media.impl.audio :as audio]
            [com.phronemophobic.clj-media.impl.video :as video]
            [com.phronemophobic.clj-media.impl.util
             :refer [distinct-by
                     interleave-all
                     str->kw
                     str->symbol
                     insert-last]]
            [com.phronemophobic.clj-media.impl.raw :as raw
             :refer :all])
  (:import
   java.io.PushbackReader
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


(defn video-filter [input-formats filter-name opts]
  (fn [rf]
    (let [filter-graph (avfilter_graph_alloc)

          input-contexts
          (into []
                (map (fn [input-format]
                       (let [buffer (avfilter_get_by_name "buffer")
                               _ (when (nil? buffer)
                                   (throw (Exception.)))
                               buffer-context (avfilter_graph_alloc_filter filter-graph buffer nil)
                               time-base (:time-base input-format)
                               args (format "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d"
                                            (:width input-format)
                                            (:height input-format)
                                            (:pixel-format input-format)
                                            (:num time-base)
                                            (:den time-base))

                               err (avfilter_init_str buffer-context args)
                               _ (when (not (zero? err))
                                   (throw (Exception.)))]
                         buffer-context)))
                input-formats)

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
                     (.setValue (:pixel-format (first input-formats))))
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

          _ (doseq [[i input-context] (map-indexed vector input-contexts)]
              (let [err (avfilter_link input-context 0
                                       filter-context i)]
                (when (not (zero? err))
                  (throw (Exception.)))))

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
         ;; flush
         (doseq [buffer-context input-contexts]
           (av_buffersrc_write_frame buffer-context nil))
         (let [result
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
                     result
                     ;; (reduced result)

                     :else
                     (reduced {:error-code err
                               :error-msg (av/error->str err)
                               :type :transcode-error}))))]
           (rf result)))
        ([result [input-idx input-frame]]
         (let [buffer-context (nth input-contexts input-idx)]
           (av_buffersrc_write_frame buffer-context
                                     input-frame))
         (loop [result result]
           (let [frame (av/new-frame)
                 ;; TODO: check av_buffersink_get* to
                 ;;       to check format types
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

(defn audio-filter [input-formats filter-name opts]
  (fn [rf]
    (let [filter-graph (avfilter_graph_alloc)

          input-contexts
          (into []
                (map (fn [input-format]
                       (let [buffer (avfilter_get_by_name "abuffer")
                             _ (when (nil? buffer)
                                 (throw (Exception.)))
                             buffer-context (avfilter_graph_alloc_filter filter-graph buffer nil)

                             args (format "channel_layout=%s:sample_fmt=%d:sample_rate=%d"
                                          (datafy-media/ch-layout->str
                                           (:ch-layout input-format ))
                                          (:sample-format input-format)
                                          (:sample-rate input-format))

                             err (avfilter_init_str buffer-context args)
                             _ (when (not (zero? err))
                                 (throw (Exception.)))]
                         buffer-context)))
                input-formats)

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
                        (.setValue (:sample-format (first input-formats))))
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

          _ (doseq [[i input-context] (map-indexed vector input-contexts)]
              (let [err (avfilter_link input-context 0
                                       filter-context i)]
                (when (not (zero? err))
                  (throw (Exception.)))))

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
         ;; flush
         (doseq [buffer-context input-contexts]
           (av_buffersrc_write_frame buffer-context nil))
         (let [result
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
                     result
                     ;; (reduced result)

                     :else
                     (reduced {:error-code err
                               :error-msg (av/error->str err)
                               :type :transcode-error}))))]
           (rf result)))
        ([result [input-index input-frame]]
         (let [buffer-context (nth input-contexts input-index)]
           (av_buffersrc_write_frame buffer-context
                                     input-frame))
         (loop [result result]
           (let [frame (av/new-frame)
                 ;; TODO: check av_buffersink_get* to
                 ;;       to check format types
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


(defrecord AVFilterMedia [filter-name opts media-type media]
  fm/IMediaSource
  (-media [this]
    (mapv (fn [src]
            (let [input-format (fm/-format src)]
              (if (= media-type (:media-type input-format))
                (let [f (case media-type
                          :media-type/audio audio-filter
                          :media-type/video video-filter)
                      frames (sequence
                              (comp (map (fn [frame]
                                           [0 frame]))
                                    ;; signal EOF
                                    ;; prevents extra buffering for some filters
                                    (insert-last [0 nil])
                                    (f [input-format] filter-name opts))
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
                               :pixel-format (:format first-frame)))]
                  (fm/->FrameSource
                   frames
                   output-format))
                ;; else, don't change
                src)))
          (fm/-media media)))
  )


(defrecord AVMultiFilterMedia [filter-name opts media-type medias]
  fm/IMediaSource
  (-media [this]
    (let [matches-media-type? #(= media-type
                                  (:media-type (fm/-format %)))
          filter-inputs (mapv (fn [media]
                                (filterv matches-media-type? (fm/-media media)))
                              medias)
          _ (when (not (every? #(= 1 (count %))
                               filter-inputs))
              (throw (ex-info (str "Filter " filter-name " expects all input media to have exactly one media of type " media-type)
                              {:filter this})))
          filter-inputs (into []
                              (map first)
                              filter-inputs)

          unfiltered-outputs (into []
                                   (comp (map fm/-media)
                                         cat
                                         (remove matches-media-type?)
                                         (distinct-by #(System/identityHashCode %)))
                                   medias)

          make-filter (case media-type
                        :media-type/audio audio-filter
                        :media-type/video video-filter)
          filter-input-formats (mapv fm/-format filter-inputs)

          frames (sequence
                  (make-filter filter-input-formats filter-name opts)
                  (apply
                   interleave-all
                   (sequence
                    (map-indexed (fn [input-index src]
                                   (sequence
                                    (map (fn [frame]
                                           [input-index frame]))
                                    (fm/-frames src))))
                    filter-inputs)))
          first-frame (first frames)
          output-format
          (case media-type
            :media-type/audio
            (assoc (first filter-input-formats)
                   :ch-layout (:ch_layout first-frame)
                   :sample-format (:format first-frame)
                   :sample-rate (:sample_rate first-frame))
            :media-type/video
            (assoc (first filter-input-formats)
                   :width (:width first-frame)
                   :height (:height first-frame)
                   :pixel-format (:format first-frame)))
          filtered-output (fm/->FrameSource frames
                                            output-format)]
      (conj unfiltered-outputs filtered-output))))


(defn concat-filter [input-formats opts]
  (fn [rf]
    (let [filter-graph (avfilter_graph_alloc)

          input-contexts
          (into []
                (map (fn [input-format]
                       (case (:media-type input-format)
                         :media-type/audio
                         (let [buffer (avfilter_get_by_name "abuffer")
                               _ (when (nil? buffer)
                                   (throw (Exception.)))
                               buffer-context (avfilter_graph_alloc_filter filter-graph buffer nil)

                               args (format "channel_layout=%s:sample_fmt=%d:sample_rate=%d"
                                            (datafy-media/ch-layout->str
                                             (:ch-layout input-format ))
                                            (:sample-format input-format)
                                            (:sample-rate input-format))

                               err (avfilter_init_str buffer-context args)
                               _ (when (not (zero? err))
                                   (throw (Exception.)))]
                           buffer-context)

                         :media-type/video
                         (let [buffer (avfilter_get_by_name "buffer")
                               _ (when (nil? buffer)
                                   (throw (Exception.)))
                               buffer-context (avfilter_graph_alloc_filter filter-graph buffer nil)
                               time-base (:time-base input-format)
                               args (format "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d"
                                            (:width input-format)
                                            (:height input-format)
                                            (:pixel-format input-format)
                                            (:num time-base)
                                            (:den time-base))

                               err (avfilter_init_str buffer-context args)
                               _ (when (not (zero? err))
                                   (throw (Exception.)))]
                           buffer-context))))
                input-formats)

          output-contexts
          (into []
                (map (fn [input-format]
                       (case (:media-type input-format)
                         :media-type/audio
                         (let [buffersink (avfilter_get_by_name "abuffersink")
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
                                                 AV_OPT_SEARCH_CHILDREN)]
                           buffersink-context)

                         :media-type/video
                         (let [buffersink (avfilter_get_by_name "buffersink")
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
                                          (.setValue (:pixel-format input-format)))
                               _ (av_opt_set_bin buffersink-context "pix_fmts"
                                                 pix-fmts
                                                 (* 1 4)
                                                 AV_OPT_SEARCH_CHILDREN)]
                           buffersink-context))))
                (take (+ (:v opts) (:a opts)) input-formats))

          ;; create the filter
          filter-name "concat"
          filter-context (avfilter_graph_alloc_filter
                          filter-graph
                          (avfilter_get_by_name filter-name)
                          nil)
          _ (assert filter-context)
          _ (set-filter-context-options filter-context filter-name opts)

          _ (avfilter_init_str filter-context nil)

          _ (doseq [[i input-context] (map-indexed vector input-contexts)]
              (let [err (avfilter_link input-context 0
                                       filter-context i)]
                (when (not (zero? err))
                  (throw (Exception.)))))

          _ (doseq [[i output-context] (map-indexed vector output-contexts)]
              (let [err (avfilter_link filter-context i
                                       output-context 0)]
                (when (not (zero? err))
                  (throw (Exception.)))))


          err (avfilter_graph_config filter-graph nil)

          time-bases
          (into {}
                (map (fn [output-context]
                       (let [time-base (av_buffersink_get_time_base output-context)]
                         [output-context time-base])))
                output-contexts)

          filter-graph* (doto (PointerByReference.)
                          (.setValue (.getPointer filter-graph)))
          ]
      (.register av/cleaner filter-graph
                 (fn []
                   (avfilter_graph_free filter-graph*)))
      (doseq [output-context output-contexts]
        (let [filter-graph-ref (volatile! filter-graph)]
          (.register av/cleaner output-context
                     (fn []
                       (vreset! filter-graph-ref nil)))))
      (when (not (>= err 0))
        (throw (Exception.)))
      (fn
        ([]
         (rf))
        ([result]
         (rf result)
         ;; flush
         (doseq [buffer-context input-contexts]
           (av_buffersrc_write_frame buffer-context nil))
         (let [result
               (reduce
                (fn [result [i output-context]]
                  (loop [result result]
                    (let [frame (av/new-frame)
                          err (av_buffersink_get_frame_flags output-context
                                                             frame
                                                             0)]
                      (cond
                        (zero? err)
                        (let [time-base (get time-bases output-context)
                              frame (doto frame
                                      (.writeField "pts"
                                                   (av_rescale_q (:pts frame)
                                                                 time-base
                                                                 (:time-base (nth input-formats i)))))
                              result (rf result [i frame])]
                          (if (reduced? result)
                            result
                            (recur result)))

                        (av/eagain? err)
                        result

                        (av/eof? err)
                        result

                        :else
                        (reduced {:error-code err
                                  :error-msg (av/error->str err)
                                  :type :transcode-error})))))
                result
                (map-indexed vector output-contexts))]
           (rf result))
         )
        ([result [input-idx input-frame]]
         (let [buffer-context (nth input-contexts input-idx)]
           (av_buffersrc_write_frame buffer-context
                                     input-frame))
         (reduce
          (fn [result [i output-context]]
            (loop [result result]
              (let [frame (av/new-frame)
                    err (av_buffersink_get_frame_flags output-context
                                                       frame
                                                       0)]
                (cond
                  (zero? err)
                  (let [time-base (get time-bases output-context)
                        frame (doto frame
                                (.writeField "pts"
                                             (av_rescale_q (:pts frame)
                                                           time-base
                                                           (:time-base (nth input-formats i)))))
                        result (rf result [i frame])]
                    (if (reduced? result)
                      result
                      (recur result)))

                  (av/eagain? err)
                  result

                  (av/eof? err)
                  result

                  :else
                  (reduced {:error-code err
                            :error-msg (av/error->str err)
                            :type :transcode-error})))))
          result
          (map-indexed vector output-contexts)))))))


(defrecord AVConcatFilterMedia [opts medias]
  fm/IMediaSource
  (-media [this]
    (let [all-medias (into []
                           (map fm/-media)
                           medias)
          stream-count (count
                        (first all-medias))
          _ (when (not (every? (fn [streams]
                                 (= (count streams)
                                    stream-count))
                               (rest all-medias)))
              (throw (ex-info "Concat filter expects all media to have the same number and kinds of streams."
                              {:filter opts
                               :medias medias})))

          stream-sort-fn (fn [format]
                           (= :media-type/audio
                              (:media-type format)))
          input-formats
          (into []
                (comp (map (fn [media]
                             (let [formats (into []
                                                 (map fm/-format)
                                                 media)]
                               (sort-by stream-sort-fn
                                        formats))))
                      cat)
                all-medias)

          opts
          {:n (count all-medias)
           :v (->> (first all-medias)
                   (filter fm/video?)
                   count)
           :a (->> (first all-medias)
                   (filter fm/audio?)
                   count)}

          ;; this mess is to try to achieve proper
          ;; interleaving
          interleaved-input-frames
          (sequence
           (comp (map-indexed
                  (fn [segment-idx media]
                    (apply
                     interleave-all
                     (sequence (map-indexed
                                (fn [stream-idx src]
                                  (let [input-idx (+ (* segment-idx stream-count)
                                                     stream-idx)]
                                    (sequence (comp
                                               (map (fn [frame]
                                                      [input-idx frame]))
                                               (insert-last [input-idx nil]))
                                              (fm/-frames src)))))
                               (sort-by
                                (fn [src]
                                  (stream-sort-fn (fm/-format src)))
                                media)))))
                 cat)
           all-medias)

          all-frames
          (sequence
           (concat-filter input-formats opts)
           interleaved-input-frames)

          frames-by-stream
          (into []
                (map (fn [i]
                       (sequence
                        (keep (fn [[stream-idx frame]]
                                (when (= stream-idx i)
                                  frame)))
                        all-frames)))
                (range stream-count))

          outputs
          (into []
                (map (fn [i]
                       (let [frames (nth frames-by-stream i)
                             input-format (nth input-formats i)
                             media-type (:media-type input-format)

                             first-frame (first frames)
                             ;; will this work for streams with different time bases?
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
                                      :pixel-format (:format first-frame)))]
                         (fm/->FrameSource frames
                                           output-format))))
                (range stream-count))]
      outputs)))



(defn filter-fn [filter-info]
  (let [opts## 'opts
        opts-or-media## 'opts-or-media

        default-input? (and (= 1 (count (:inputs filter-info)))
                            (= "default" (-> filter-info
                                             :inputs
                                             first
                                             :name)))

        inputs (if default-input?
                 '[media]
                 (into []
                       (comp (map :name)
                             (map str->symbol))
                       (:inputs filter-info)))

        filter-name (:name filter-info)
        fn-name (str->symbol filter-name)

        supported-options (filterv supported-filter-option? (:options filter-info))
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
             (when (not default-input?)
               (str
                "\n\n"
                "Inputs: " (str/join
                            ","
                            (eduction
                             (map :name)
                             (:inputs filter-info)))))
             "\n\n"
             "Supported options:

"
             (clojure.string/join
              "\n\n"
              (eduction
               (map (fn [{:keys [name type help default-val min max unit]}]
                      (str (str->kw name) " - " help
                           "\n"
                           (clojure.string/join
                            "\n"
                            (eduction
                             (remove nil?)
                             (map #(str "\t" %))
                             (if-let [const (get consts unit)]
                               (let [has-none? (and (zero? min)
                                                    (not (some (fn [c]
                                                                 (zero? (-> c :default-val :int)))
                                                               const)))
                                     const (if has-none?
                                             (conj const {:name "none"})
                                             const)]
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
                                         const)))])
                               (case type
                                 :avoption-type/duration
                                 [(str "type: duration in microseconds" )
                                  (when (not (map? default-val))
                                    (str "default: " default-val))
                                  (str "min: " min)
                                  (str "max: " max)]

                                 :avoption-type/bool
                                 [(str "type: " (clojure.core/name type))
                                  (when (not (map? default-val))
                                    (str "default: " default-val))
                                  "values: true, false"]

                                 ;; else
                                 [(str "type: " (clojure.core/name type))
                                  (when (not (map? default-val))
                                    (str "default: " default-val))
                                  (str "min: " min)
                                  (str "max: " max)])))))))
               (sort-by :name supported-options)))

             "\n\n"
             (let [unsupported-options
                   (eduction
                    (remove supported-filter-option?)
                    (remove #(= (:type %)
                                :avoption-type/const))
                    (map :name)
                    (:options filter-info))]
               (when (seq unsupported-options)
                 (str
                  "Unsupported options: "
                  (clojure.string/join ", "
                                       unsupported-options)))))

        media-type (-> filter-info
                       :inputs
                       first
                       :media-type)]
    `(defn ~fn-name
       ~doc-string
       ([~@inputs]
        (~fn-name nil ~@inputs))
       ([~opts ~@inputs]
        ~(if (= 1 (count inputs))
           `(->AVFilterMedia ~filter-name ~opts## ~media-type ~(first inputs))
           `(->AVMultiFilterMedia ~filter-name ~opts## ~media-type ~inputs))))))


(defn supported-filter-type? [filter-info]
  (and (= 1 (count (:outputs filter-info)))
       (every? #(= (:media-type (first (:inputs filter-info)))
                   (:media-type %))
               (rest (:inputs filter-info)))
       (= (:media-type (first (:outputs filter-info)))
          (:media-type (first (:inputs filter-info))))))

(defmacro make-fns []
  `(do
     ~@(into []
             (comp (filter supported-filter-type?)
                   (map filter-fn))
             (list-filters))))



(defrecord ForceFormat [format media]
  fm/IMediaSource
  (-media [this]
    (mapv (fn [src]
            (fm/->FrameSource
             (fm/-frames src)
             (merge
              (fm/-format src)
              (datafy-media/map->format format))))
          (fm/-media media))))

(defn force-format
  "Used for testing."
  [format media]
  (->ForceFormat format media))

(defrecord Swscale [opts output-format media]
  fm/IMediaSource
  (-media [this]
    (mapv (fn [src]
            (if (fm/video? src)
              (let [input-format (fm/-format src)
                    output-format
                    (merge
                     input-format
                     (datafy-media/map->format output-format
                                               :media-type/video))]
                (fm/->FrameSource
                 (sequence
                  (video/swscale input-format output-format opts)
                  (fm/-frames src))
                 output-format))
              src))
          (fm/-media media))))

(defn swscale [opts output-format media]
  (->Swscale opts output-format media))

