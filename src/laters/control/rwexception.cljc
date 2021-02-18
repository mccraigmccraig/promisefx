(ns laters.control.rwexception
  (:require
   [laters.abstract.monad.protocols :as m.p]
   [laters.abstract.context.protocols :as ctx.p]
   [laters.abstract.monad :as m]
   [laters.abstract.error.protocols :as err.p]
   [laters.abstract.error :as error]
   [laters.control.reader.protocols :as m.r.p]
   [laters.control.writer.protocols :as m.w.p]
   [laters.control.maybe :as maybe]
   [laters.control.identity :as id]
   [laters.abstract.tagged :as tagged]
   [laters.abstract.runnable.protocols :as runnable.p]
   [laters.monoid :as monoid]))

(defrecord Failure [e]
  ctx.p/Extract
  (-extract [_] e))

(defn failure
  [e]
  (->Failure e))

(defn failure?
  [v]
  (instance? Failure v))

(defrecord RWExceptionVal [ctx f]
  ctx.p/Contextual
  (-get-context [_] ctx)
  ctx.p/Extract
  (-extract [_] f)
  runnable.p/IRunnable
  (-run [_ arg]
    (f arg)))

(defn rwexception-val
  [ctx f]
  (->RWExceptionVal ctx f))

(defn rwexception-val?
  [v]
  (instance? RWExceptionVal v))

(defn plain-rwexception-val
  [ctx v]
  (rwexception-val
   ctx
   (fn [_]
     {:monad.writer/output nil
      :monad/val v})))

(defn error-rw-exception-body
  ([e] (error-rw-exception-body nil e))
  ([output e]
   (merge
    {:monad.writer/output nil}
    output
    {:monad/val (failure e)})))

(defn error-rwexception-val
  ([ctx e] (error-rwexception-val ctx nil e))
  ([ctx output e]
   (rwexception-val
    ctx
    (fn [_]
      (error-rw-exception-body output e)))))

(defn rw-exception-t-bind
  "a short-circuiting bind - whether success or failure
   values are bound depends on the short-circuit? predicate"
  [output-ctx inner-ctx short-circuit? m inner-mv inner-mf]
  (m.p/-bind
     inner-ctx
     inner-mv

     (fn outer-mf [outer-mv]
       (assert (rwexception-val? outer-mv))

       (m.p/-return
        inner-ctx
        (rwexception-val
         m
         (fn [{env :monad.reader/env}]

           (try
             (let [{w :monad.writer/output
                    v :monad/val
                    :as r} (runnable.p/-run outer-mv {:monad.reader/env env})]

               (if (short-circuit? v)

                 r

                 (try
                   (let [inner-mv' (inner-mf (ctx.p/-extract v))]

                     (m.p/-bind
                      inner-ctx
                      inner-mv'

                      (fn outer-mf' [outer-mv']
                        (assert (rwexception-val? outer-mv'))

                        (let [{w' :monad.writer/output
                               v' :monad/val} (runnable.p/-run
                                               outer-mv'
                                               {:monad.reader/env env})]
                          {:monad.writer/output (monoid/mappend
                                                 output-ctx
                                                 nil
                                                 w
                                                 w')
                           :monad/val v'}))))
                   (catch Exception e
                     (error-rw-exception-body
                      {:monad.writer/output (monoid/mappend
                                             output-ctx
                                             nil
                                             w)}
                      e)))))
             (catch Exception e
               (error-rw-exception-body e)))))))))

(deftype RWExceptionTCtx [output-ctx inner-ctx]
  ctx.p/Context
  (-get-type [m] (ctx.p/-get-type inner-ctx))
  m.p/Monad
  (-bind [m inner-mv inner-mf]
    (rw-exception-t-bind
     output-ctx
     inner-ctx
     failure?
     m
     inner-mv
     inner-mf))

  (-return [m v]
    (m.p/-return inner-ctx (plain-rwexception-val m v)))

  err.p/MonadError
  (-reject [m v]
    (m.p/-return inner-ctx (error-rwexception-val v)))
  (-catch [m inner-mv inner-mf]
    ;; catch is just bind for failure
    (rw-exception-t-bind
     output-ctx
     inner-ctx
     (complement failure?)
     m
     inner-mv
     inner-mf))
  (-finally [m mv f])

  m.r.p/MonadReader
  (-ask [m]
    (m.p/-return
     inner-ctx
     (rwexception-val
      m
      (fn [{env :monad.reader/env}]
        {:monad.writer/output nil
         :monad/val env}))))
  (-local [m f mv]
    (m.p/-return
     inner-ctx
     (rwexception-val
      m
      (fn [{env :monad.reader/env}]
        (runnable.p/-run mv {:monad.reader/env (f env)})))))

  m.w.p/MonadWriter
  (-tell [m v]
    (m.p/-return
     inner-ctx
     (rwexception-val
      m
      (fn [{env :monad.reader/env}]
        {:monad.writer/output (monoid/mappend output-ctx nil v)}))))
  (-listen [m mv]
    (m.p/-return
     inner-ctx
     (rwexception-val
      m
      (fn [{env :monad.reader/env}]
        (let [{w :monad.writer/output
               :as lv} (runnable.p/-run mv {:monad.reader/env env})]
          {:monad.writer/output w
           :monad/val lv})))))
  (-pass [m mv]
    (m.p/-return
     inner-ctx
     (rwexception-val
      m
      (fn [{env :monad.reader/env}]
        (let [{w :monad.writer/output
               [val f] :monad/val} (runnable.p/-run mv {:monad.reader/env env})]
          {:monad.writer/output (f w)
           :monad/val val}))))))

(def rwexception-ctx
  (->RWExceptionTCtx
   monoid/map-monoid-ctx
   (id/->IdentityCtx [::RWExceptionT ::monoid/map ::id/Identity])))

(def tagged-rwexception-ctx
  (->RWExceptionTCtx
   monoid/map-monoid-ctx
   (tagged/->TaggedCtx [::RWExceptionT ::monoid/map ::tagged/Tagged] nil)))

(comment

 (r/run
   (m/mlet rwx/rwexception-ctx
     (e/catch
         (m/mlet rwx/rwexception-ctx
           [a (m/return 10)
            b (reader/local inc (reader/ask))
            _ (writer/tell [:foo 10])]
           (throw (ex-info "boo" {}))
           (m/return (+ a b)))
         (fn [e] (m/return (.getMessage e)))))
   {:monad.reader/env 100})

 {:monad.writer/output nil,
  :monad/val "boo"}






 )