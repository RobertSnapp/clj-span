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
;;; This namespace defines the coastal-storm-protection model.
;;;
;;;  Lookup the storm name.
;;;  Create a function to determine the wave's new orientation.
;;;  Discover the storm direction.
;;;  Project a swath of carriers 100km wide perpendicular to the storm direction.
;;;  Deplete all sinks that the wave intersects.
;;;  Move the carriers together to their new positions along the wavefront and repeat the sink depletion process.
;;;  If users are encountered, store a carrier on the user and keep going (non-rival use).
;;;  If a carrier's possible-weight falls below the threshold, stop the carrier.
;;;  Exit when all carriers have finished moving.

(ns clj-span.coastal-storm-protection-model
  (:use [clj-span.gui        :only (draw-layer)]
        [clj-span.params     :only (*trans-threshold*)]
        [clj-span.model-api  :only (distribute-flow service-carrier)]
        [clj-misc.utils      :only (seq2map p & angular-distance)]
        [clj-misc.matrix-ops :only (get-rows
                                    get-cols
                                    make-matrix
                                    map-matrix
                                    matrix2seq
                                    add-ids
                                    subtract-ids
                                    in-bounds?
                                    on-bounds?
                                    filter-matrix-for-coords
                                    get-neighbors
                                    find-point-at-dist-in-m
                                    find-line-between)]
        [clj-misc.randvars   :only (_0_ _+_ rv-fn rv-above?)]))

(defn handle-sink-effects
  [current-id possible-weight actual-weight eco-sink-layer geo-sink-layer]
  (let [eco-sink-cap     (get-in eco-sink-layer current-id)
        geo-sink-cap     (get-in geo-sink-layer current-id)
        eco-sink?        (not= _0_ eco-sink-cap)
        geo-sink?        (not= _0_ geo-sink-cap)
        post-geo-possible-weight (if geo-sink?
                                   (rv-fn (fn [p g] (- p (min p g))) possible-weight geo-sink-cap)
                                   possible-weight)
        post-geo-actual-weight   (if geo-sink?
                                   (rv-fn (fn [a g] (- a (min a g))) actual-weight geo-sink-cap)
                                   actual-weight)
        post-eco-actual-weight   (if (and eco-sink? (not= _0_ post-geo-actual-weight))
                                   (rv-fn (fn [a e] (- a (min a e))) post-geo-actual-weight eco-sink-cap)
                                   post-geo-actual-weight)
        eco-sink-effects         (if (and eco-sink? (not= _0_ post-geo-actual-weight))
                                   {current-id (rv-fn min post-geo-actual-weight eco-sink-cap)})]
    [post-geo-possible-weight post-eco-actual-weight eco-sink-effects]))

(defn apply-local-effects!
  [storm-orientation eco-sink-layer geo-sink-layer use-layer cache-layer
   possible-flow-layer actual-flow-layer rows cols
   {:keys [route possible-weight actual-weight sink-effects] :as storm-carrier}]
  (let [current-location (peek route)
        next-location    (add-ids current-location storm-orientation)
        [new-possible-weight new-actual-weight new-sink-effects] (handle-sink-effects current-location
                                                                                      possible-weight
                                                                                      actual-weight
                                                                                      eco-sink-layer
                                                                                      geo-sink-layer)
        post-sink-carrier (assoc storm-carrier
                            :possible-weight new-possible-weight
                            :actual-weight   new-actual-weight
                            :sink-effects    (merge-with _+_ sink-effects new-sink-effects))]
    (dosync
     (alter (get-in possible-flow-layer current-location) _+_ possible-weight)
     (alter (get-in actual-flow-layer   current-location) _+_ actual-weight))
    (if (not= _0_ (get-in use-layer current-location))
      (dosync (alter (get-in cache-layer current-location) conj post-sink-carrier)))
    (if (and (in-bounds? rows cols next-location)
             (rv-above? new-possible-weight *trans-threshold*))
      (assoc post-sink-carrier :route (conj route next-location)))))

(def *animation-sleep-ms* 1000)

(defn run-animation [panel]
  (send-off *agent* run-animation)
  (Thread/sleep *animation-sleep-ms*)
  (doto panel (.repaint)))

(defn end-animation [panel] panel)

(defn distribute-wave-energy!
  [storm-source-point storm-orientation storm-carriers get-next-orientation
   eco-sink-layer geo-sink-layer use-layer cache-layer rows cols]
  (println "Moving the wave energy toward the coast...")
  (let [possible-flow-layer    (make-matrix rows cols (fn [_] (ref _0_)))
        actual-flow-layer      (make-matrix rows cols (fn [_] (ref _0_)))
        possible-flow-animator (agent (draw-layer "Possible Flow" possible-flow-layer :possible-flow 1))
        actual-flow-animator   (agent (draw-layer "Actual Flow"   actual-flow-layer   :actual-flow   1))]
    (send-off possible-flow-animator run-animation)
    (send-off actual-flow-animator   run-animation)
    (doseq [_ (take-while (& seq last)
                          (iterate
                           (fn [[storm-centerpoint storm-orientation storm-carriers]]
                             (let [next-storm-centerpoint (add-ids storm-centerpoint storm-orientation)
                                   next-storm-orientation (get-next-orientation next-storm-centerpoint storm-orientation)
                                   next-storm-carriers    (doall (remove nil? (pmap (p apply-local-effects!
                                                                                       storm-orientation
                                                                                       eco-sink-layer
                                                                                       geo-sink-layer
                                                                                       use-layer
                                                                                       cache-layer
                                                                                       possible-flow-layer
                                                                                       actual-flow-layer
                                                                                       rows
                                                                                       cols)
                                                                                    storm-carriers)))]
                               (if (and next-storm-orientation
                                        (not (on-bounds? rows cols storm-centerpoint)))
                                 [next-storm-centerpoint next-storm-orientation next-storm-carriers]
                                 (println "Location-dependent termination:" next-storm-orientation storm-centerpoint))))
                           [storm-source-point storm-orientation storm-carriers]))]
      (print "*") (flush))
    (send-off possible-flow-animator end-animation)
    (send-off actual-flow-animator   end-animation))
  (println "\nAll done."))

(def storm-to-wave-orientations
     {[ 0  1] [ 1  0]
      [ 1  1] [ 1 -1]
      [ 1  0] [ 0 -1]
      [ 1 -1] [-1 -1]
      [ 0 -1] [-1  0]
      [-1 -1] [-1  1]
      [-1  0] [ 0  1]
      [-1  1] [ 1  1]})

(defn find-wave-line
  [storm-source-point storm-orientation wave-width cell-width cell-height rows cols]
  (let [wave-orientation (storm-to-wave-orientations storm-orientation) ;; 90 degrees left of storm-orientation
        wave-reach       (/ wave-width 2)
        wave-left-edge   (find-point-at-dist-in-m storm-source-point
                                                  wave-orientation
                                                  wave-reach
                                                  cell-width
                                                  cell-height)
        wave-right-edge  (find-point-at-dist-in-m storm-source-point
                                                  (map - wave-orientation)
                                                  wave-reach
                                                  cell-width
                                                  cell-height)]
    (filter (p in-bounds? rows cols) (find-line-between wave-left-edge wave-right-edge))))

(defn get-storm-orientation
  [on-track? rows cols id current-orientation]
  (if-let [on-track-neighbors (seq (filter on-track? (get-neighbors rows cols id)))]
    (let [orientation-deltas (seq2map (map #(subtract-ids % id) on-track-neighbors)
                                      (fn [neighbor-orientation]
                                        [(angular-distance current-orientation neighbor-orientation)
                                         neighbor-orientation]))]
      (orientation-deltas (apply min (keys orientation-deltas))))))

;; FIXME: find a way to specify the wave width and initial storm
;;        orientation through the flow-params map.
(def *wave-width* 100000) ;; in meters
(def *initial-storm-orientation* [0 -1]) ;; start the storm to the West

;; FIXME: Source and Sink values should be scaled properly by the size
;;        of the cells they inhabit.  This is not being done correctly
;;        now, so running the simulation at a higher resolution will
;;        make the waves die down faster (since the sinks will be
;;        doubled).
(defmethod distribute-flow "CoastalStormMovement"
  [_ cell-width cell-height source-layer eco-sink-layer use-layer
   {storm-track-layer "StormTrack", geo-sink-layer "GeomorphicFloodProtection"}]
  (println "Running Coastal Storm Protection flow model.")
  (let [rows          (get-rows source-layer)
        cols          (get-cols source-layer)
        cache-layer   (make-matrix rows cols (fn [_] (ref ())))
        source-points (filter-matrix-for-coords (p not= _0_) source-layer)]
    (println "Source points:" (count source-points))
    (let [storm-source-point   (first source-points) ;; we are only going to use one source point in this model
          on-track?            #(not= _0_ (get-in storm-track-layer %))
          get-next-orientation (p get-storm-orientation on-track? rows cols)
          storm-orientation    (get-next-orientation storm-source-point *initial-storm-orientation*)
          wave-line            (find-wave-line storm-source-point storm-orientation *wave-width* cell-width cell-height rows cols)
          wave-height          (get-in source-layer storm-source-point)
          storm-carriers       (map #(struct-map service-carrier
                                       :source-id       %
                                       :route           [%]
                                       :possible-weight wave-height
                                       :actual-weight   wave-height
                                       :sink-effects    {})
                                    wave-line)]
      (distribute-wave-energy! storm-source-point
                               storm-orientation
                               storm-carriers
                               get-next-orientation
                               eco-sink-layer
                               geo-sink-layer
                               use-layer
                               cache-layer
                               rows
                               cols)
      (println "Users affected:" (count (filter (& seq deref) (matrix2seq cache-layer))))
      (println "Simulation complete. Returning the cache-layer.")
      (map-matrix (& seq deref) cache-layer))))
