;;; Copyright 2009 Gary Johnson
;;;
;;; This file is part of CLJ-SPAN.
;;;
;;; CLJ-SPAN is free software: you can redistribute it and/or modify
;;; it under the terms of the GNU General Public License as published
;;; by the Free Software Foundation, either version 3 of the License,
;;; or (at your option) any later version.
;;;
;;; CLJ-SPAN is distributed in the hope that it will be useful, but
;;; WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;;; General Public License for more details.
;;;
;;; You should have received a copy of the GNU General Public License
;;; along with CLJ-SPAN.  If not, see <http://www.gnu.org/licenses/>.

(ns span.location-builder
  (:use [misc.utils        :only (maphash mapmap seq2map)]
        [misc.matrix-ops   :only (get-neighbors)]
        [span.randvars     :only (unpack-datasource rv-zero)]))
(refer 'corescience :only '(find-state
                            find-observation
                            get-state-map))

(defstruct location :id :neighbors :source :sink :use :flow-features :carrier-cache)

(defn- extract-values-by-concept-clean
  "Returns a seq of the concept's values in the observation,
   which are doubles or probability distributions."
  [obs conc rows cols n]
  (when conc
    (unpack-datasource (find-state obs conc) rows cols n)))

(defn- extract-values-by-concept
  "Returns a seq of the concept's values in the observation,
   which are doubles or probability distributions."
  [obs conc rows cols n]
  (when conc
    (println "EXTRACT-VALUES-BY-CONCEPT...")
    (println "OBS:  " obs)
    (println "CONC: " conc)
    (println "STATE:" (find-state obs conc))
    (unpack-datasource (find-state obs conc) rows cols n)))

(defn- extract-all-values-clean
  "Returns a map of concept-names to vectors of doubles or probability
   distributions."
  [obs conc rows cols n]
  (when conc
    (maphash (memfn getLocalName) #(vec (unpack-datasource % rows cols n)) (get-state-map (find-observation obs conc)))))

(defn- extract-all-values
  "Returns a map of concept-names to vectors of doubles or probability
   distributions."
  [obs conc rows cols n]
  (when conc
    (let [subobs (find-observation obs conc)
          states (get-state-map subobs)]
      (println "EXTRACT-ALL-VALUES...")
      (println "OBS:   " obs)
      (println "CONC:  " conc)
      (println "SUBOBS:" subobs)
      (println "STATES:" states)
      (maphash (memfn getLocalName) #(vec (unpack-datasource % rows cols n)) (get-state-map (find-observation obs conc))))))

#_(defn make-location-map-alternate
    "Returns a map of ids to location objects, one per location in the
     data layers."
    [source-layer sink-layer use-layer flow-layers]
    (let [rows (get-rows source-layer)
          cols (get-cols source-layer)]
      (into {}
            (for [i (range rows) j (range cols) :let [id [i j]]]
              [id (struct-map location
                    :id            id
                    :neighbors     (get-neighbors rows cols id)
                    :source        (get-in source-layer id)
                    :sink          (get-in sink-layer   id)
                    :use           (get-in use-layer    id)
                    :flow-features (mapmap identity #(get-in % id) flow-layers)
                    :carrier-cache (atom ()))]))))

(defn make-location-map
  "Returns a map of ids to location objects, one per location in the
   observation set."
  [observation source-conc sink-conc use-conc flow-conc rows cols scaled-rows scaled-cols]
  (let [n             (* rows cols)
        scaled-n      (* scaled-rows scaled-cols)
        flow-vals-map (extract-all-values observation flow-conc rows cols n)]
    (println "Rows x Cols:" rows "x" cols)
    (println "Scaled-Rows x Scaled-Cols:" scaled-rows "x" scaled-cols)
    (println "Flow-vals-map length:" (count flow-vals-map))
    (seq2map
     (map (fn [[i j] source sink use]
            (struct-map location
              :id            [i j]
              :neighbors     (vec (get-neighbors scaled-rows scaled-cols [i j]))
              :source        source
              :sink          sink
              :use           use
              :flow-features (maphash identity #(% (+ (* i scaled-cols) j)) flow-vals-map)))
	      ;;:carrier-cache (atom ()))) ;; FIXME make carrier-cache into a [] to save memory (do vecs save memory?)
          (for [i (range scaled-rows) j (range scaled-cols)] [i j])
          (or (extract-values-by-concept observation source-conc rows cols n) (replicate scaled-n rv-zero))
          (or (extract-values-by-concept observation sink-conc   rows cols n) (replicate scaled-n rv-zero))
          (or (extract-values-by-concept observation use-conc    rows cols n) (replicate scaled-n rv-zero)))
     (fn [loc] [(:id loc) loc]))))
