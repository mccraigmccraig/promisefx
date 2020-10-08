(ns laters.concurrency.promise.rxjava
  (:require
   [laters.concurrency.promise.protocols :as p]
   [promesa.core :as promesa])
  (:import
   [io.reactivex.subjects SingleSubject]
   [io.reactivex Single SingleObserver]))

(deftype SingleSubjectPromiseFactory []
  p/IPromiseFactory
  (-type [ctx]
    [::SingleSubjectPromise])
  (-resolved [ctx v]
    (SingleSubject/just v))
  (-rejected [ctx err]
    (SingleSubject/error err)))

(defn ss-flatten
  [out p]
  (if (not (instance? Single p))
    (.onSuccess out p)

    (.subscribeWith
     p
     (reify SingleObserver
       (onSubscribe [_ _])
       (onError [_ err]
         (.onError out err))
       (onSuccess [_ success]
         (.onSuccess out success))))))

(defn ss-then
  ([p f]
   (let [ss (SingleSubject/create)]
     (.subscribeWith
      p
      (reify SingleObserver
        (onSubscribe [_ _])
        (onError [_ err]
          (.onError ss err))
        (onSuccess [_ success]
          (try
            (ss-flatten ss (f success))
            (catch Exception x
              (.onError ss x))))))
     ss)))

(defn ss-handle
  ([p f]
   (let [ss (SingleSubject/create)]
     (.subscribeWith
      p
      (reify SingleObserver
        (onSubscribe [_ _])
        (onError [_ err]
          (try
            (ss-flatten ss (f nil err))
            (catch Exception x
              (.onError ss x))))
        (onSuccess [_ success]
          (try
            (ss-flatten ss (f success nil))
            (catch Exception x
              (.onError ss x))))))
     ss)))

(defn ss-deref
  [p]
  (let [pp (promesa/deferred)]
    (.subscribeWith
     p
     (reify SingleObserver
       (onSubscribe [_ _])
       (onError [_ err]
         (promesa/reject! pp err))
       (onSuccess [_ success]
         (promesa/resolve! pp success))))

    (deref pp)))

(extend Single
  p/IPromise
  {:-then ss-then
   :-handle ss-handle
   :-deref ss-deref})

(def factory (SingleSubjectPromiseFactory.))