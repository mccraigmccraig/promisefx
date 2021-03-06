(ns promisefx.control.exception
  (:require
   [promisefx.context.protocols :as ctx.p]
   [promisefx.fx.monad.protocols :as m.p]
   [promisefx.fx.error.protocols :as err.p]
   [promisefx.data.extractable.protocols :as extractable.p]
   [promisefx.data.success-failure :as s.f]
   [promisefx.data.tagged.protocols :as tag.p]
   [promisefx.control.identity :as ctrl.id]
   [promisefx.control.tagged :as ctrl.tag]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ExceptionCtx
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; values are Failure|anything-else
;; Failures short-circuit
;; thrown Exceptions get caught and wrapped in a Failure
;; anything can be put in a Failure with error/reject
;; Failures can be caught with error/catch
;; finally behaviour with error/finally

(deftype ExceptionTCtx [tag inner-ctx]
  ctx.p/Context
  (-get-tag [outer-ctx] tag)

  m.p/Monad
  (-bind [outer-ctx inner-mv inner-mf]
    (m.p/-bind
     inner-ctx
     inner-mv
     (fn outer-mf
       [outer-mv]
       (try
         (if (s.f/failure? outer-mv)
           (m.p/-return inner-ctx outer-mv)
           (inner-mf outer-mv))
         (catch #?(:clj Exception :cljs :default) e
           (m.p/-return
            inner-ctx
            (s.f/failure outer-ctx e)))))))
  (-return [outer-ctx v]
    (m.p/-return inner-ctx v))

  err.p/MonadError
  (-reject [outer-ctx v]
    (m.p/-return inner-ctx (s.f/failure outer-ctx v)))
  (-catch [outer-ctx inner-mv inner-mf]
    (m.p/-bind
     inner-ctx
     inner-mv
     (fn outer-mf
       [outer-mv]
       (if (s.f/failure? outer-mv)
         (try
           (inner-mf (extractable.p/-extract outer-mv))
           (catch #?(:clj Exception :cljs :default) e
             (m.p/-return inner-ctx (s.f/failure outer-ctx e))))
         (m.p/-return inner-ctx outer-mv)))))
  (-finally [outer-ctx inner-mv inner-mf]
    inner-mv))

(def untagged-ctx
  (->ExceptionTCtx
   [::ExceptionT ::ctrl.id/Identity]
   ctrl.id/ctx))

(def default-tag [::ExceptionT ::ctrl.tag/Tagged])

(def ctx
  (->ExceptionTCtx
   default-tag
   (ctrl.tag/->TaggedCtx default-tag nil)))

;; we make this context's tag the default tag for
;; any values - so the error effect can be used with
;; arbitrary plain values
(extend-protocol tag.p/Tagged
  Object
  (-get-tag [_] default-tag))

(def boxed-tagged-ctx
  (->ExceptionTCtx
   [::ExceptionT ::ctrl.tag/BoxedTagged]
   (ctrl.tag/->BoxedTaggedCtx [::ExceptionT ::ctrl.tag/BoxedTagged] nil)))
