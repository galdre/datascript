(ns datascript.util
  #?(:clj
     (:import
       [java.util UUID]))
  (:refer-clojure :exclude [find]))

(def ^:dynamic *debug*
  false)

#?(:clj
   (defmacro log [& body]
     (when (System/getProperty "datascript.debug")
       `(when *debug*
          (println ~@body)))))

#?(:clj
   (def ^:private ^:dynamic *if+-syms))
  
#?(:clj
   (defn- if+-rewrite-cond-impl [cond]
     (clojure.core/cond
       (empty? cond)
       true
    
       (and
         (= :let (first cond))
         (empty? (second cond)))
       (if+-rewrite-cond-impl (nnext cond))
    
       (= :let (first cond))
       (let [[var val & rest] (second cond)
             sym                (gensym)]
         (vswap! *if+-syms conj [var sym])
         (list 'let [var (list 'clojure.core/vreset! sym val)]
           (if+-rewrite-cond-impl
             (cons 
               :let
               (cons rest
                 (nnext cond))))))
    
       :else
       (list 'and
         (first cond)
         (if+-rewrite-cond-impl (next cond))))))

#?(:clj
   (defn- if+-rewrite-cond [cond]
     (binding [*if+-syms (volatile! [])]
       [(if+-rewrite-cond-impl cond) @*if+-syms])))

#?(:clj
   (defn- flatten-1 [xs]
     (vec
       (mapcat identity xs))))

#?(:clj
   (defmacro if+
     "Allows sharing local variables between condition and then clause.
      
      Use `:let [...]` form (not nested!) inside `and` condition and its bindings
      will be visible in later `and` clauses and inside `then` branch:
      
        (if+ (and
               (= 1 2)
               ;; same :let syntax as in doseq/for
               :let [x 3
                     y (+ x 4)]
               ;; x and y visible downstream
               (> y x))
          
          ;; then: x and y visible here!
          (+ x y 5)
          
          ;; else: no x or y
          6)"
     [cond then else]
     (if (and
           (seq? cond)
           (or
             (= 'and (first cond))
             (= 'clojure.core/and (first cond))))
       (let [[cond' syms] (if+-rewrite-cond (next cond))]
         `(let ~(flatten-1
                  (for [[_ sym] syms]
                    [sym '(volatile! nil)]))
            (if ~cond'
              (let ~(flatten-1
                      (for [[binding sym] syms]
                        [binding (list 'deref sym)]))
                ~then)
              ~else)))
       (list 'if cond then else))))

(defn- rand-bits [pow]
  (rand-int (bit-shift-left 1 pow)))

#?(:cljs
   (defn- to-hex-string [n l]
     (let [s (.toString n 16)
           c (count s)]
       (cond
         (> c l) (subs s 0 l)
         (< c l) (str (apply str (repeat (- l c) "0")) s)
         :else   s))))

(defn squuid
  ([]
   (squuid #?(:clj  (System/currentTimeMillis)
              :cljs (.getTime (js/Date.)))))
  ([msec]
   #?(:clj
      (let [uuid     (UUID/randomUUID)
            time     (int (/ msec 1000))
            high     (.getMostSignificantBits uuid)
            low      (.getLeastSignificantBits uuid)
            new-high (bit-or (bit-and high 0x00000000FFFFFFFF)
                       (bit-shift-left time 32)) ]
        (UUID. new-high low))
      :cljs
      (uuid
        (str
          (-> (int (/ msec 1000))
            (to-hex-string 8))
          "-" (-> (rand-bits 16) (to-hex-string 4))
          "-" (-> (rand-bits 16) (bit-and 0x0FFF) (bit-or 0x4000) (to-hex-string 4))
          "-" (-> (rand-bits 16) (bit-and 0x3FFF) (bit-or 0x8000) (to-hex-string 4))
          "-" (-> (rand-bits 16) (to-hex-string 4))
          (-> (rand-bits 16) (to-hex-string 4))
          (-> (rand-bits 16) (to-hex-string 4)))))))

(defn squuid-time-millis
  "Returns time that was used in [[squuid]] call, in milliseconds, rounded to the closest second."
  [uuid]
  #?(:clj (-> (.getMostSignificantBits ^UUID uuid)
            (bit-shift-right 32)
            (* 1000))
     :cljs (-> (subs (str uuid) 0 8)
             (js/parseInt 16)
             (* 1000))))

(defn distinct-by [f coll]
  (->>
    (reduce
      (fn [[seen res :as acc] el]
        (let [key (f el)]
          (if (contains? seen key)
            acc
            [(conj! seen key) (conj! res el)])))
      [(transient #{}) (transient [])]
      coll)
    second
    persistent!))

(defn find [pred xs]
  (reduce
    (fn [_ x]
      (when (pred x)
        (reduced x)))
    nil xs))

(defn removem [key-pred m]
  (persistent!
    (reduce-kv
      (fn [m k v]
        (if (key-pred k)
          m
          (assoc! m k v)))
      (transient (empty m)) m)))
