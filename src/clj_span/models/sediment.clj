;;; Copyright 2010 Gary Johnson
;;;
;;; This file is part of clj-span.
;;;
;;; clj-span is free software: you can redistribute it and/or modify
;;; it under the terms of the GNU General Public License as published
;;; by the Free Software Foundation, either version 3 of the License,
;;; or (at your option) any later version.
;;;
;;; clj-span is distributed in the hope that it will be useful, but
;;; WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;;; General Public License for more details.
;;;
;;; You should have received a copy of the GNU General Public License
;;; along with clj-span.  If not, see <http://www.gnu.org/licenses/>.
;;;
;;;-------------------------------------------------------------------
;;;
;;; This namespace defines the sediment model.
;;;
;;; FIXME: Model updates due to conversation with Ken on 10/15/10:
;;;
;;; Three classes of sinks: floodplains, dams/reservoirs, water intakes
;;; Two class of beneficiaries: farmers in floodplains, avoided turbidity beneficiaries (dams and water intakes)
;;;
;;; In order to distinguish their behavior in this model, I need layers
;;; for the presence/absence of floodplains, dams/reservoirs, water
;;; intakes, and farmers passed as flow dependencies in the span
;;; statement.
;;;

(ns clj-span.models.sediment
  (:use [clj-span.core       :only (distribute-flow! service-carrier)]
        [clj-misc.utils      :only (seq2map mapmap iterate-while-seq with-message
                                    memoize-by-first-arg angular-distance p def-
                                    with-progress-bar-cool euclidean-distance)]
        [clj-misc.matrix-ops :only (get-neighbors on-bounds? add-ids subtract-ids find-nearest
                                    find-line-between rotate-2d-vec find-point-at-dist-in-m)]
        [clj-misc.varprop    :only (_0_ _+_ *_ _d rv-fn _min_)]))

(defn- lowest-neighbors
  [id in-stream? elevation-layer rows cols]
  (if-not (on-bounds? rows cols id)
    (let [neighbors      (if (in-stream? id)
                           ;; Step downstream
                           (filter in-stream? (get-neighbors rows cols id))
                           ;; Step downhill
                           (get-neighbors rows cols id))
          local-elev     (get-in elevation-layer id)
          neighbor-elevs (map (p get-in elevation-layer) neighbors)
          min-elev       (reduce _min_ local-elev neighbor-elevs)]
      (filter #(= min-elev (get-in elevation-layer %)) neighbors))))
(def- lowest-neighbors (memoize-by-first-arg lowest-neighbors))

(defn- nearest-to-bearing
  [bearing id neighbors]
  (if (seq neighbors)
    (if bearing
      (let [bearing-changes (seq2map neighbors
                                     #(let [bearing-to-neighbor (subtract-ids % id)]
                                        [(angular-distance bearing bearing-to-neighbor)
                                         %]))]
        (bearing-changes (apply min (keys bearing-changes))))
      (first neighbors))))

;; FIXME: Somehow this still doesn't terminate correctly for some carriers.
(defn- find-next-step
  [id in-stream? elevation-layer rows cols bearing]
  (let [prev-id (if bearing (subtract-ids id bearing))]
    (nearest-to-bearing bearing
                        id
                        (remove (p = prev-id)
                                (lowest-neighbors id
                                                  in-stream?
                                                  elevation-layer
                                                  rows
                                                  cols)))))
(def- find-next-step (memoize find-next-step))

;; FIXME: Must merge sink-AFs with sink-caps or our math is wrong.
(defn- handle-sink-and-use-effects!
  "Computes the amount sunk by each sink encountered along an
   out-of-stream flow path. Reduces the sink-caps for each sink which
   captures some of the service medium. Returns remaining
   actual-weight and the local sink effects."
  [current-id sink-stream-intakes sink-AFs sink-caps use-id? cache-layer ha-per-cell
   {:keys [possible-weight actual-weight sink-effects stream-bound?] :as sediment-carrier}]
  (if (= _0_ actual-weight)
    ;; Skip all computations, since there's no sediment left in this
    ;; carrier anyway.
    [actual-weight {}]
    (if stream-bound?
      ;; We're in the stream. Spread the collected source weights
      ;; latitudinally among all sinks in the floodplain. Activation
      ;; factors must be applied to the sinks before they are used.
      (if-let [affected-sink (sink-stream-intakes current-id)]
        (let [sink-cap-ref (sink-caps affected-sink)
              sink-AF      (sink-AFs  affected-sink)]
          (dosync
           (let [sink-cap (*_ sink-AF (deref sink-cap-ref))]
             (if (= _0_ sink-cap)
               [actual-weight {}]
               (let [amount-sunk              (rv-fn (fn [a s] (min a s))         actual-weight sink-cap)
                     carrier-weight-remaining (rv-fn (fn [a s] (max (- a s) 0.0)) actual-weight sink-cap)]
                 (alter sink-cap-ref #(rv-fn (fn [a s] (- s (min a (* sink-AF s)))) actual-weight %))
                 (if (use-id? affected-sink)
                   ;; A user is co-located with this sink in the
                   ;; floodplain.  This user receives as actual-weight
                   ;; the amount-sunk by its sink.  We will not record
                   ;; that sink's effect in the sink-effects map as it
                   ;; is not blocking access to upstream sediment.
                   (dosync (alter (get-in cache-layer affected-sink) conj
                                  (assoc sediment-carrier
                                    :route           nil
                                    :possible-weight (_d possible-weight ha-per-cell)
                                    :actual-weight   (_d amount-sunk     ha-per-cell)
                                    :sink-effects    (mapmap identity #(_d % ha-per-cell) sink-effects)))))
                 [carrier-weight-remaining {affected-sink amount-sunk}])))))
        [actual-weight {}])
      ;; Not in the stream. Only one source weight and one sink. Activation factors don't matter.
      (if-let [sink-cap-ref (sink-caps current-id)]
        (dosync
         (let [sink-cap (deref sink-cap-ref)]
           (if (= _0_ sink-cap)
             [actual-weight {}]
             (do
               (alter sink-cap-ref #(rv-fn (fn [a s] (max (- s a) 0.0)) actual-weight %))
               [(rv-fn (fn [a s] (max (- a s) 0.0)) actual-weight sink-cap)
                {current-id (rv-fn (fn [a s] (min a s)) actual-weight sink-cap)}]))))
        [actual-weight {}]))))

(def- *max-levee-distance* 100.0) ;; in meters

(defn- nearby-levees
  [origin bearing levee? cell-width cell-height]
  (let [left-dir     (rotate-2d-vec (/ Math/PI 2.0) bearing)
        right-dir    (map - left-dir)
        left-bounds  (find-point-at-dist-in-m origin left-dir  *max-levee-distance* cell-width cell-height)
        right-bounds (find-point-at-dist-in-m origin right-dir *max-levee-distance* cell-width cell-height)]
    (seq (filter levee? (find-line-between left-bounds right-bounds)))))
(def- nearby-levees (memoize-by-first-arg nearby-levees))

;; FIXME: Make sure carriers can hop from stream to stream as necessary.
;; FIXME: Add possible-weight and actual-weight to the entire
;; latitudinal floodplain stripe around a stream-bound carrier's
;; location.
(defn- to-the-ocean!
  "Computes the state of the sediment-carrier after it takes another
   step downhill.  If it encounters a sink location, it drops some
   sediment according to the remaining sink capacity at this location.
   If it encounters a use location, a service-carrier is stored in the
   user's carrier-cache."
  [cache-layer possible-flow-layer actual-flow-layer ha-per-cell sink-caps use-id? levee?
   in-stream? sink-stream-intakes sink-AFs elevation-layer cell-width cell-height rows cols
   {:keys [route possible-weight actual-weight sink-effects stream-bound?] :as sediment-carrier}]
  (try
    (let [current-id (peek route)
          prev-id    (peek (pop route))
          bearing    (if prev-id (subtract-ids current-id prev-id))]
      (dosync
       (alter (get-in possible-flow-layer current-id) _+_ (_d possible-weight ha-per-cell))
       (alter (get-in actual-flow-layer   current-id) _+_ (_d actual-weight   ha-per-cell)))
      (if (and stream-bound? bearing (nearby-levees current-id bearing levee? cell-width cell-height))
        ;; Levees channel the water, so floodplain sinks and users will
        ;; not be affected.
        (if-let [next-id (find-next-step current-id in-stream? elevation-layer rows cols bearing)]
          (assoc sediment-carrier
            :route         (conj route next-id)
            :stream-bound? (in-stream? next-id)))
        ;; Either we're over-land or there are no levees nearby, so we
        ;; may proceed with the local checks.
        (let [[new-actual-weight new-sink-effects] (handle-sink-and-use-effects! current-id
                                                                                 sink-stream-intakes
                                                                                 sink-AFs
                                                                                 sink-caps
                                                                                 use-id?
                                                                                 cache-layer
                                                                                 ha-per-cell
                                                                                 sediment-carrier)]
          (if-let [next-id (find-next-step current-id in-stream? elevation-layer rows cols bearing)]
            (assoc sediment-carrier
              :route           (conj route next-id)
              :actual-weight   new-actual-weight
              :sink-effects    (merge-with _+_ sink-effects new-sink-effects)
              :stream-bound?   (in-stream? next-id))))))
    (catch Exception _ (println "Bad agent go BOOM!"))))

(defn- stop-unless-reducing
  [n coll]
  (take-while (fn [[p c]] (> p c)) (partition 2 1 (map count (take-nth n coll)))))

(defn- propagate-sediment!
  "Constructs a sequence of sediment-carrier objects (one per
   in-stream source id) and then iteratively propagates them downhill
   until they run out of sediment, reach a stream location, get stuck
   in a low elevation point, or fall off the map bounds.  Once they
   reach a stream location, the carriers will attempt to continue
   downhill while staying in a stream course.  All the carriers are
   moved together in timesteps (more or less)."
  [cache-layer possible-flow-layer actual-flow-layer source-layer source-points
   ha-per-cell sink-caps levee? in-stream? sink-stream-intakes sink-AFs use-id?
   elevation-layer cell-width cell-height rows cols]
  (with-message "Moving the sediment carriers downhill and downstream...\n" "All done."
    (dorun
     (stop-unless-reducing
      100
      (iterate-while-seq
       (fn [sediment-carriers]
         (let [on-land-carriers   (count (remove :stream-bound? sediment-carriers))
               in-stream-carriers (- (count sediment-carriers) on-land-carriers)]
           (printf "Carriers: %10d | On Land: %10d | In Stream: %10d%n"
                   (+ on-land-carriers in-stream-carriers)
                   on-land-carriers
                   in-stream-carriers)
           (flush)
           (pmap (p to-the-ocean!
                    cache-layer
                    possible-flow-layer
                    actual-flow-layer
                    ha-per-cell
                    sink-caps
                    use-id?
                    levee?
                    in-stream?
                    sink-stream-intakes
                    sink-AFs
                    elevation-layer
                    cell-width
                    cell-height
                    rows
                    cols)
                 sediment-carriers)))
       (map
        #(let [source-weight (*_ ha-per-cell (get-in source-layer %))]
           (struct-map service-carrier
             :source-id       %
             :route           [%]
             :possible-weight source-weight
             :actual-weight   source-weight
             :sink-effects    {}
             :stream-bound?   (in-stream? %)))
        (remove (p on-bounds? rows cols) source-points)))))))

(defn- make-buckets
  [ha-per-cell layer active-points]
  (seq2map active-points (fn [id] [id (ref (*_ ha-per-cell (get-in layer id)))])))

;; FIXME: Should we be considering the elevation of our data-point?
(defn- flood-activation-factors
  "Returns a map of each data-id (e.g. a sink or use location) to a
   number between 0.0 and 1.0, representing its relative position
   between the stream edge (1.0) and the floodplain boundary (0.0)."
  [in-floodplain? in-stream-map]
  (with-message "Computing flood activation factors...\n" "\nAll done."
    (into {}
          (with-progress-bar-cool
            :keep
            (count in-stream-map)
            (for [[in-stream-id data-id] in-stream-map]
              (if (= in-stream-id data-id)
                ;; location is already in-stream, activation is 100%
                [data-id 1.0]
                ;; location is out-of-stream, activation is scaled
                ;; by the relative distance between this location,
                ;; the in-stream proxy location, and the nearest
                ;; floodplain boundary
                (let [loc-delta       (subtract-ids data-id in-stream-id)
                      inside-id       (last (take-while in-floodplain? (iterate (p add-ids loc-delta) data-id)))
                      outside-id      (add-ids inside-id loc-delta)
                      boundary-id     (first (remove in-floodplain? (find-line-between inside-id outside-id)))
                      run-to-boundary (euclidean-distance in-stream-id boundary-id)
                      run-to-data     (euclidean-distance in-stream-id data-id)]
                  [data-id (- 1.0 (/ run-to-data run-to-boundary))])))))))

(defn- find-nearest-stream-point!
  [in-stream? in-stream-map rows cols id]
  (dosync
   (let [available-id? (complement @in-stream-map)
         stream-point  (find-nearest #(and (in-stream? %) (available-id? %)) rows cols id)]
     (if stream-point (alter in-stream-map conj [stream-point id])))))

(defn- find-nearest-stream-points
  [in-stream? rows cols ids]
  (with-message
    "Finding nearest stream points..."
    #(str "done. [Shifted " (count %) " ids]")
    (let [in-stream-ids (filter in-stream? ids)
          in-stream-map (ref (zipmap in-stream-ids in-stream-ids))]
      (dorun
       (pmap (p find-nearest-stream-point! in-stream? in-stream-map rows cols)
             (remove in-stream? ids)))
      @in-stream-map)))

;; FIXME: 100-yr vs. 500-yr floodplains?
(defmethod distribute-flow! "SedimentTransport"
  [_ cell-width cell-height rows cols cache-layer possible-flow-layer actual-flow-layer
   source-layer sink-layer _ source-points sink-points use-points
   {stream-layer "River", elevation-layer "Altitude", levees-layer "Levee",
    floodplain-layer "FloodplainsCode"}]
  (let [levee?               (memoize #(if-let [val (get-in levees-layer     %)] (not= _0_ val)))
        in-stream?           (memoize #(if-let [val (get-in stream-layer     %)] (not= _0_ val)))
        in-floodplain?       (memoize #(if-let [val (get-in floodplain-layer %)] (not= _0_ val)))
        floodplain-sinks     (filter in-floodplain? sink-points)
        sink-stream-intakes  (find-nearest-stream-points in-stream? rows cols floodplain-sinks)
        sink-AFs             (flood-activation-factors in-floodplain? sink-stream-intakes)
        use-id?              (set use-points)
        ha-per-cell          (* cell-width cell-height (Math/pow 10.0 -4.0))
        sink-caps            (make-buckets ha-per-cell sink-layer floodplain-sinks)]
    (propagate-sediment! cache-layer
                         possible-flow-layer
                         actual-flow-layer
                         source-layer
                         source-points
                         ha-per-cell
                         sink-caps
                         levee?
                         in-stream?
                         sink-stream-intakes
                         sink-AFs
                         use-id?
                         elevation-layer
                         cell-width
                         cell-height
                         rows
                         cols)))
