(ns com.phronemophobic.clj-media.impl.video
  (:require [clojure.java.io :as io]
            [com.phronemophobic.clj-media.impl.av :as av]
            [com.phronemophobic.clj-media.impl.datafy
             :as datafy-media]
            [com.phronemophobic.clj-media.impl.raw :as raw
             :refer :all]
            [clojure.pprint :refer [pprint]])
  (:import
   com.sun.jna.Memory
   com.sun.jna.Pointer
   java.awt.image.BufferedImage
   com.sun.jna.ptr.PointerByReference
   com.sun.jna.ptr.IntByReference
   com.sun.jna.ptr.ByteByReference
   com.sun.jna.Structure)
  (:gen-class))

(raw/import-structs!)

(defn pf [& args]
  (apply prn args)
  (flush))

(defn transcode-frame2 [{:keys [width height pix_fmt time_base]}
                        outout-pix-fmt]
  (fn [rf]
    (let [frame (av_frame_alloc)

          filter-graph (avfilter_graph_alloc)

          buffer (avfilter_get_by_name "buffer")
          _ (when (nil? buffer)
              (throw (Exception.)))
          buffer-context (avfilter_graph_alloc_filter filter-graph buffer "src")
          args (format "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d"
                       width
                       height
                       pix_fmt
                       1
                       60
                       #_#_(.readField time_base "num")
                       (.readField time_base "den"))
          
          err (avfilter_init_str buffer-context args)
          _ (when (not (zero? err))
              (throw (Exception.)))

          buffersink (avfilter_get_by_name "buffersink")
          _ (when (nil? buffersink)
              (throw (Exception.)))
          buffersink-context* (PointerByReference.)
          _ (avfilter_graph_create_filter buffersink-context*
                                          buffersink
                                          "sink"
                                          nil
                                          nil
                                          filter-graph)

          buffersink-context (.getValue buffersink-context*)

          pix-fmts (doto (IntByReference.)
                     (.setValue outout-pix-fmt))
          _ (av_opt_set_bin buffersink-context "pix_fmts"
                            pix-fmts
                            (* 1 4)
                            AV_OPT_SEARCH_CHILDREN)

          err (avfilter_link buffer-context 0
                             buffersink-context 0)
          _ (when (not (zero? err))
              (throw (Exception.)))
          err (avfilter_graph_config filter-graph nil)]
      (when (not (>= err 0))
        (throw (Exception.)))
      (fn
        ([]
         (rf))
        ([result]
         (av_frame_free (doto (PointerByReference.)
                          (.setValue (.getPointer frame))))
         (avfilter_graph_free
          (doto (PointerByReference.)
            (.setValue (.getPointer filter-graph))))
         (rf result))
        ([result input-frame]
         (av_buffersrc_add_frame buffer-context
                                 input-frame)
         (loop [result result]
           (let [err (av_buffersink_get_frame_flags buffersink-context
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

(defn transcode-frame3 [{:keys [width height pixel-format time-base]}
                        output-pix-fmt]
  (fn [rf]
    (let [filter-graph (avfilter_graph_alloc)

          buffer (avfilter_get_by_name "buffer")
          _ (when (nil? buffer)
              (throw (Exception.)))
          buffer-context (avfilter_graph_alloc_filter filter-graph buffer "src")
          args (format "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d"
                       width
                       height
                       pixel-format
                       ;; 1
                       ;; 60
                       (.readField time-base "num")
                       (.readField time-base "den"))
          err (avfilter_init_str buffer-context args)
          _ (when (not (zero? err))
              (throw (Exception.)))

          buffersink (avfilter_get_by_name "buffersink")
          _ (when (nil? buffersink)
              (throw (Exception.)))
          buffersink-context* (PointerByReference.)
          _ (avfilter_graph_create_filter buffersink-context*
                                          buffersink
                                          "sink"
                                          nil
                                          nil
                                          filter-graph)

          buffersink-context (.getValue buffersink-context*)

          pix-fmts (doto (IntByReference.)
                     (.setValue output-pix-fmt))
          _ (av_opt_set_bin buffersink-context "pix_fmts"
                            pix-fmts
                            (* 1 4)
                            AV_OPT_SEARCH_CHILDREN)

          err (avfilter_link buffer-context 0
                             buffersink-context 0)
          _ (when (not (zero? err))
              (throw (Exception.)))
          err (avfilter_graph_config filter-graph nil)]
      (when (not (>= err 0))
        (throw (Exception.)))
      (fn
        ([]
         (rf))
        ([result]
         (avfilter_graph_free
          (doto (PointerByReference.)
            (.setValue (.getPointer filter-graph))))
         (rf result))
        ([result input-frame]
         (av_buffersrc_write_frame buffer-context
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

(defn transcode-frame [decoder-context pix-fmt]
  (fn [rf]
    (let [frame (av_frame_alloc)

          filter-graph (avfilter_graph_alloc)

          buffer (avfilter_get_by_name "buffer")
          _ (when (nil? buffer)
              (throw (Exception.)))
          buffer-context (avfilter_graph_alloc_filter filter-graph buffer "src")
          time-base (.readField decoder-context "time_base")
          args (format "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d"
                       (.readField decoder-context "width")
                       (.readField decoder-context "height")
                       (.readField decoder-context "pix_fmt")
                       (.readField time-base "num")
                       (.readField time-base "den"))
          
          err (avfilter_init_str buffer-context args)
          _ (when (not (zero? err))
              (throw (Exception.)))

          buffersink (avfilter_get_by_name "buffersink")
          _ (when (nil? buffersink)
              (throw (Exception.)))
          buffersink-context* (PointerByReference.)
          _ (avfilter_graph_create_filter buffersink-context*
                                          buffersink
                                          "sink"
                                          nil
                                          nil
                                          filter-graph)

          buffersink-context (.getValue buffersink-context*)

          pix-fmts (doto (IntByReference.)
                     (.setValue pix-fmt))
          _ (av_opt_set_bin buffersink-context "pix_fmts"
                            pix-fmts
                            (* 1 4)
                            AV_OPT_SEARCH_CHILDREN)

          err (avfilter_link buffer-context 0
                             buffersink-context 0)
          _ (when (not (zero? err))
              (throw (Exception.)))
          err (avfilter_graph_config filter-graph nil)]
      (when (not (>= err 0))
        (throw (Exception.)))
      (fn
        ([]
         (rf))
        ([result]
         (av_frame_free (doto (PointerByReference.)
                          (.setValue (.getPointer frame))))
         (avfilter_graph_free
          (doto (PointerByReference.)
            (.setValue (.getPointer filter-graph))))
         (rf result))
        ([result input-frame]
         (av_buffersrc_add_frame buffer-context
                                 input-frame)
         (loop [result result]
           (let [err (av_buffersink_get_frame_flags buffersink-context
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

(defmacro with-tile [[tile-bind img] & body]
  `(let [img# ~img
         ~tile-bind (.getWritableTile img# 0 0)]
     (try
       ~@body
       (finally
         (.releaseWritableTile img# 0 0)))))

(def ^:private pixel-format->buffered-image-format
  {(datafy-media/kw->pixel-format :pixel-format/rgb24) BufferedImage/TYPE_3BYTE_BGR
   (datafy-media/kw->pixel-format :pixel-format/rgba) BufferedImage/TYPE_4BYTE_ABGR
   (datafy-media/kw->pixel-format :pixel-format/rgb555le) BufferedImage/TYPE_USHORT_555_RGB
   (datafy-media/kw->pixel-format :pixel-format/rgb565le) BufferedImage/TYPE_USHORT_565_RGB
   })



(defn render-frame
  "Writes an AVFrame into a BufferedImage. Assumes :byte-bgr image format."
  [img frame]
  (let [
        width (.readField frame "width")
        height (.readField frame "height")
        linesize (-> frame
                     (.readField "linesize")
                     (nth 0))
        buf-ptr (-> frame
                    (.readField "data")
                    (nth 0)
                    (.getPointer))

        get-buf
        (condp contains? (.getType img)
          #{BufferedImage/TYPE_3BYTE_BGR
            BufferedImage/TYPE_4BYTE_ABGR} (fn [y] (.getByteArray buf-ptr (* linesize y) linesize))
          #{BufferedImage/TYPE_USHORT_555_RGB
            BufferedImage/TYPE_USHORT_565_RGB} (fn [y] (.getShortArray buf-ptr (* linesize y) (/ linesize 2))))]

    (with-tile [wraster img]
      (doseq [y (range height)]
        (.setDataElements wraster 0 y width 1
                          (get-buf y))))))

(defn frame->img [frame]
  (let [format (pixel-format->buffered-image-format (:format frame))
        _ (assert format)
        width (:width frame)
        height (:height frame)
        img (BufferedImage. width height format)]
    (render-frame img frame)
    img))


(defn find-encoder-codec [output-format-context {:keys [width
                                                        height
                                                        time_base
                                                        framerate
                                                        gop_size
                                                        max_b_frames
                                                        pix_fmt]}]
  (let [
        output-format (:oformat output-format-context)
        output-format+ (Structure/newInstance AVOutputFormatByReference
                                              output-format)
        codec-id (:video_codec output-format+ )

        output-codec (avcodec_find_encoder codec-id)
        _ (when (nil? output-codec)
            (throw (Exception. "could not find encoder")))

        encoder-context (avcodec_alloc_context3 output-codec)
        _ (when (nil? encoder-context)
            (throw (Exception. "Could not create encoder")))

        ;; num-channels 2
        ;; sample-rate (int 44100)
        #_#_sample-fmt (-> output-codec
                       (:sample_fmts )
                       (.getValue))

    ;; if (codec->id == AV_CODEC_ID_H264)
    ;;     av_opt_set(c->priv_data, "preset", "slow", 0);

        gop_size (or gop_size 12)

        ;; check pix_fmts? from codec for defaults
        ;; pix-fmt (or pix-fmt AV_PIX_FMT_YUV420P)

        _ (doto encoder-context
            (.writeField "width" (int width))
            (.writeField "height" (int height))
            ;; (.writeField "time_base" time_base)
            ;; (.writeField "framerate" framerate)
            (.writeField "gop_size" (int gop_size))
            (.writeField "max_b_frames" (int max_b_frames))
            (.writeField "pix_fmt" pix_fmt)
            )
        _ (doto (.readField encoder-context "time_base")
            (.writeField "num" (:num time_base))
            (.writeField "den" (:den time_base)))

        ;; _ (doto (.readField encoder-context "framerate")
        ;;     (.writeField "num" (:num framerate))
        ;;     (.writeField "den" (:den framerate)))

        _ (when (not (zero?
                      (bit-and (:flags output-format+)
                               AVFMT_GLOBALHEADER)))
            (doto encoder-context
              (.writeField "flags"
                           (int (bit-or (:flags encoder-context )
                                        AV_CODEC_FLAG_GLOBAL_HEADER)))))
        err (avcodec_open2 encoder-context output-codec nil)
        _ (when (neg? err)
            (throw (ex-info "Could not open codec"
                            {:err err})))

        ;; stream
        stream (avformat_new_stream output-format-context output-codec)
        _ (when (nil? stream)
            (throw (Exception. "Could not create stream.")))
        _ (prn "stream index" (:index stream))

        _ (doto stream
            (.writeField "time_base" (:time_base encoder-context )))

        _ (def my-stream stream)
        ;; error = avcodec_parameters_from_context(stream->codecpar, avctx);
        err (avcodec_parameters_from_context (:codecpar stream )
                                             encoder-context)]
    (when (neg? err)
      (throw (Exception. "Could not initialize stream params")))
    encoder-context))


(def  SWS_FAST_BILINEAR     1)
(def  SWS_BILINEAR          2)
(def  SWS_BICUBIC           4)
(def  SWS_X                 8)
(def  SWS_POINT          0x10)
(def  SWS_AREA           0x20)
(def  SWS_BICUBLIN       0x40)
(def  SWS_GAUSS          0x80)
(def  SWS_SINC          0x100)
(def  SWS_LANCZOS       0x200)
(def  SWS_SPLINE        0x400)

(defn swscale
  ([input-format output-format]
   (swscale input-format output-format nil))
  ([input-format output-format opts]
   (let [sws-ctx (doto (sws_alloc_context)
                   (datafy-media/set-option ["swscale" :srcw] :srcw (:width input-format))
                   (datafy-media/set-option ["swscale" :srch] :srch (:height input-format))
                   (datafy-media/set-option ["swscale" :src-format] :src-format (:pixel-format input-format))
                   (datafy-media/set-option ["swscale" :dstw] :dstw (:width output-format))
                   (datafy-media/set-option ["swscale" :dsth] :dsth (:height output-format))
                   (datafy-media/set-option ["swscale" :dst-format] :dst-format (:pixel-format output-format)))
         _ (doseq [[k v] opts]
             (datafy-media/set-option sws-ctx ["swscale" k] k v))

         _ (sws_init_context sws-ctx nil nil)
         _ (when (nil? sws-ctx)
             (throw (Exception. "Error creating sws context.")))
         sws-ctx-ptr (Pointer/nativeValue sws-ctx)
         _ (.register av/cleaner sws-ctx
                      (fn []
                        (sws_freeContext sws-ctx-ptr)))]
     (fn [rf]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input-frame]
          (if input-frame
            (let [output-frame (av/new-frame)
                  err (sws_scale_frame sws-ctx output-frame input-frame)]
              (when (neg? err)
                (throw (Exception. "Error scaling frame.")))
              (.writeField output-frame "pts" (:pts input-frame))
              (rf result output-frame))
            ;; else nothing to do
            result)))))))

(defn frame->buf [frame]
  (let [buf-size (first (:linesize frame))
        buf (.getByteBuffer (:extended_data frame)
                            0
                            buf-size)]
    buf))

