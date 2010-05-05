;;; Copyright 2010 Gary Johnson
;;;
;;; This file is part of clj-misc.
;;;
;;; clj-misc is free software: you can redistribute it and/or modify
;;; it under the terms of the GNU General Public License as published
;;; by the Free Software Foundation, either version 3 of the License,
;;; or (at your option) any later version.
;;;
;;; clj-misc is distributed in the hope that it will be useful, but
;;; WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;;; General Public License for more details.
;;;
;;; You should have received a copy of the GNU General Public License
;;; along with clj-misc.  If not, see <http://www.gnu.org/licenses/>.
;;;
;;;-------------------------------------------------------------------
;;;
;;; This namespace defines a number of functions for creating,
;;; querying, and manipulating matrices, which are defined to be
;;; vectors of vectors.

(ns clj-misc.matrix-ops)

(defn get-rows [matrix] (count matrix))
(defn get-cols [matrix] (count (first matrix)))

(defn is-matrix?
  [matrix]
  (and
   (instance? clojure.lang.PersistentVector matrix)
   (instance? clojure.lang.PersistentVector (first matrix))))

(defn make-matrix
  "Creates a rows x cols vector of vectors whose states are
   generated by repeated independent calls to val-fn."
  [rows cols val-fn]
  (vec (for [_ (range rows)]
	 (vec (for [_ (range cols)]
		(val-fn))))))

(defn filter-matrix-for-coords
  [pred? matrix]
  (filter (fn [id] (pred? (get-in matrix id)))
	  (for [i (range (get-rows matrix)) j (range (get-cols matrix))] [i j])))

(defn subtract-ids
  [[a b] [c d]]
  [(- a c) (- b d)])

(def #^{:private true} delta-codes {[ 0  1] (byte 1)
				    [ 1  1] (byte 2)
				    [ 1  0] (byte 4)
				    [ 1 -1] (byte 8)
				    [ 0 -1] (byte 16)
				    [-1 -1] (byte 32)
				    [-1  0] (byte 64)
				    [-1  1] (byte 128)})

(defn bitpack-route
  [id-seq]
  (byte-array (map (comp delta-codes subtract-ids) (rest id-seq) id-seq)))

(defn seq2matrix
  "Creates a rows x cols vector of vectors whose states are
   the successive elements of aseq."
  [rows cols aseq]
  {:pre [(== (count aseq) (* rows cols))]}
  (vec (map vec (partition cols aseq))))

(defn grids-align?
  "Verifies that all matrices have the same number of rows and
   columns."
  [& matrices]
  (and (== 1 (count (distinct (map get-rows matrices))))
       (== 1 (count (distinct (map get-cols matrices))))))

(defn map-matrix
  "Maps a function f over the values in matrix, returning a new
   matrix."
  ([f matrix]
     (vec (map (fn [row] (vec (map f row))) matrix)))
  ([f matrix & matrices]
     {:pre [(apply grids-align? matrix matrices)]}
     (let [matrices (cons matrix matrices)]
       (vec (for [i (range (get-rows matrix))]
	      (vec (for [j (range (get-cols matrix))]
		     (apply f (map #(get-in % [i j]) matrices)))))))))

(defn downsample-matrix
  "Takes a vector of vectors and aggregates the values in grid chunks
   of size downscaling-factor x downscaling-factor by applying the
   aggregator-fn to each group of values.  If the size of the matrix
   is not divisible by the downscaling-factor, the remaining cells
   will be lost in the downsampled result."
  [downscaling-factor aggregator-fn matrix]
  {:pre [(>= downscaling-factor 1)]}
  (if (== downscaling-factor 1)
    matrix
    (let [rows         (get-rows matrix)
	  cols         (get-cols matrix)
	  scaled-rows  (int (/ rows downscaling-factor))
	  scaled-cols  (int (/ cols downscaling-factor))
	  offset-range (range downscaling-factor)]
      (vec (for [scaled-i (range scaled-rows)]
	     (vec (for [scaled-j (range scaled-cols)]
		    (let [i (* scaled-i downscaling-factor)
			  j (* scaled-j downscaling-factor)]
		      (aggregator-fn (for [i-offset offset-range j-offset offset-range]
				       (get-in matrix [(+ i i-offset) (+ j j-offset)])))))))))))

(defn get-neighbors
  "Return a sequence of neighboring points within the map bounds."
  [[i j] rows cols]
  (filter (fn [[i j]] (and (>= i 0) (>= j 0) (< i rows) (< j cols)))
	  (map #(vector (+ i %1) (+ j %2))
	       [-1 -1 -1  0 0  1 1 1]
	       [-1  0  1 -1 1 -1 0 1])))

(defn print-matrix
  ([matrix]
     (dotimes [i (get-rows matrix)]
       (dotimes [j (get-cols matrix)]
	 (print (get-in matrix [i j])))
       (newline)))
  ([matrix & matrices]
     (print-matrix matrix)
     (newline)
     (apply print-matrix matrices)))

(defn printf-matrix
  "Pretty prints a matrix to *out* according to format-string. Index
   [0,0] will be on the bottom left corner."
  ([matrix]
     (print-matrix matrix "%3s "))
  ([matrix format-string]
     (doseq [row (reverse (seq matrix))]
       (doseq [elt (seq row)]
         (printf format-string elt))
       (newline))))

(defn matrix-mult
  "Returns a new matrix whose values are the element-by-element
   products of the values in A and B."
  [A B]
  (map-matrix * A B))

(defn matrix-max
  "Returns the maximum value in the matrix."
  [matrix]
  (apply max (for [row (seq matrix)] (apply max (seq row)))))

(defn normalize-matrix
  "Normalizes the values in the matrix to the interval [0,1]."
  [matrix]
  (let [max-val (matrix-max matrix)]
    (if (zero? max-val)
      matrix
      (map-matrix #(/ % max-val) matrix))))

(defn find-line-between
  "Returns the sequence of all points [i j] intersected by the line
   from provider to beneficiary.  Since this is calculated over a
   regular integer-indexed grid, diagonal lines will be approximated
   by lines bending at right angles along the p-to-b line.  This
   calculation imagines the indeces of each point to be located at the
   center of a square of side length 1.  Note that the first point in
   each path will be the provider id, and the last will be the
   beneficiary id.  If provider=beneficiary, the path will contain
   only this one point."
  [[pi pj] [bi bj]]
  (let [m (if (not= pj bj) (/ (- bi pi) (- bj pj)))
	b (if m (- pi (* m pj)))
	f (fn [x] (+ (* m x) b))]
    (cond (nil? m) (map (fn [i] [i pj])
			(if (< pi bi)
			  (range pi (inc bi))
			  (range pi (dec bi) -1)))

	  (== m 0) (map (fn [j] [pi j])
			(if (< pj bj)
			  (range pj (inc bj))
			  (range pj (dec bj) -1)))

	  :otherwise (let [get-i-range
			   (cond (and (< pi bi) (< pj bj))
				 (fn [j] (let [left-i  (int (Math/round (f (- j (if (== j pj) 0.0 0.5)))))
					       right-i (int (Math/round (f (+ j (if (== j bj) 0.0 0.5)))))]
					   (range left-i (inc right-i))))
					       
				 (and (< pi bi) (> pj bj))
				 (fn [j] (let [left-i  (int (Math/round (f (- j (if (== j bj) 0.0 0.5)))))
					       right-i (int (Math/round (f (+ j (if (== j pj) 0.0 0.5)))))]
					   (range right-i (inc left-i))))
					       
				 (and (> pi bi) (< pj bj))
				 (fn [j] (let [left-i  (int (Math/round (f (- j (if (== j pj) 0.0 0.5)))))
					       right-i (int (Math/round (f (+ j (if (== j bj) 0.0 0.5)))))]
					   (range left-i  (dec right-i) -1)))
					       
				 (and (> pi bi) (> pj bj))
				 (fn [j] (let [left-i  (int (Math/round (f (- j (if (== j bj) 0.0 0.5)))))
					       right-i (int (Math/round (f (+ j (if (== j pj) 0.0 0.5)))))]
					   (range right-i (dec left-i)  -1))))
			   j-range (if (< pj bj)
				     (range pj (inc bj))
				     (range pj (dec bj) -1))]
		       (for [j j-range i (get-i-range j)] [i j])))))
