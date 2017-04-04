(ns latte-kernel.norm
  "Normalization and beta-equivalence."
  (:require [latte-kernel.utils :as utils :refer [vconcat]]
            [latte-kernel.syntax :as stx]
            [latte-kernel.defenv :as defenv :refer [definition? theorem? axiom? special?]]))


;;{
;; # Normalization
;;
;; The semantics of the lambda-calculus relies on a fundamental rewrite rule: *beta-reduction*.
;;
;; The process of *normalization* is the repeated application of the beta-reduction rule until
;; it is not possible to do so. A normalization algorithm applies a *rewrite strategy* to
;; apply the beta-reductions in some (most often) deterministic way.
;;
;; Because LaTTe extends the λ-calculus with explicit definitions, a dedicated rewriting process
;; is defined for unfolding definitions when needed. This is based on another reduction rule
;; named *delta-reduction*.
;;
;; Finally, the *specials* (expained below) are handled by a third rewrite principle
;; that we name *sigma-reduction*.
;;
;; Thus unlike the classical λ-calculus, we need to handle three reduction principles at once
;; in the normalization process.
;;
;; A type theory requires the normalization process to ensure two fundamental propertie:
;;
;;   1. *strong normalization*: it is not possible to rewrite a given term infinitely, which
;;   means that the normalization algorithm must terminate.
;;
;;   2. *confluence*: different strategies can be followed for reducing a given term - 
;;   the normalization process is non-deterministics - but the ultimate result must be the same (up-to alpha-equivalence)
;;
;; Strong normalization is as its name implies a very strong constraint, and in general
;; separate λ-calculi aimed at logical reasoning - that enjoy the property - and those
;; that are aimed at programming - for which the requirement is much too strong because
;; partial functions are not supported. Confluence in the programming case is associated
;; to a given strategy: strict or lazy (sometimes associated to a form of parallelism).
;; In the logic case, the normalization process is confluent regardless of the chosen strategy.
;;
;;
;; The LaTTe calculus is a type theory aimed at logical reasoning and thus enjoys the two properties.
;; There is no formal proof of it, but despite all of our effort we never observed any failure
;; as of today. Moreover, the formal definition of the calculus is available in the
;; *Type Theory and Formal Proof: an Introduction* (TTFP) book. There strong normalization and
;; confluence are discussed at length.
;;
;; By way of consequence, for each term `t` the normalization process produces a term `t'` that
;; is unique up-to alpha-equivalence. This term is named the *normal form* of `t`.
;;
;; We will now describe the normalization process in details.
;;
;;}



;;{
;; ## Beta-reduction

;; At the heart of a λ-calculus lies *beta-reduction*.
;; The reduction rule is based on a principle of substitution for (free occurrences of) variables
;; by arbitrary terms, which is far from being a simple thing.

;; Here is an example of a somewhat tricky example (resulting from an old tricky bug in the LaTTe kernel):

;; ```
;;  ((lambda [z :type] (lambda [x :type] (x y))) x)
;;   ~~> (lambda [x :type] (x x)    is wrong
;;
;;   ((lambda [z :type] (lambda [x :type] (x z))) x)
;; = ((lambda [z :type] (lambda [y :type] (y z))) x)
;;   ~~> (lambda [y :type] (y x)    is correct
;; ```
;;
;; What is called a *redex* (reducible expression) is simply the application of
;; a λ-abstraction on a term.
;;}

(defn redex?
  "Is `t` a reducible expression ?"
  [t]
  (and (stx/app? t)
       (stx/lambda? (first t))))


(defn beta-reduction
  "The basic rule of *beta-reduction* for term `t`.
  Note that the term `t` must already be a *redex*
  so that the rewrite can be performed."
  [t]
  (if (redex? t)
    (let [[[_ [x _] body] rand] t]
      (stx/subst body x rand))
    (throw (ex-info "Cannot beta-reduce. Not a redex" {:term t}))))

;;{
;; #### The normalization strategy
;;
;; The most important principle in the normalization process
;; is the way *redexes* are discovered. For this, a *strategy*
;; must be implemented. It is a kind of a *black art* of not
;; spending too much time looking for them, but also ensuring
;; that all of them are found. LaTTe focuses on the latter.
;;}
  
  
(declare beta-step-args)

(defn beta-step
  "A call to this function will reduce a (somewhat)
  arbitrary number of *redexes* in term `t`
   using a mostly bottom-up strategy, and reducing
 all terms at the same level (e.g. definition arguments).

The return value is a pair `[t' red?]` with `t'` the
potentially rewritten version of `t` and `red?` is `true`
 iff at least one redex was found and reduced."
  [t]
  (cond
    ;; binder
    (stx/binder? t)
    (let [[binder [x ty] body] t
          ;; 1) try reduction in binding type
          [ty' red-1?] (beta-step ty)
          ;; 2) also try reduction in body
          [body' red-2?] (beta-step body)]
      [(list binder [x ty'] body') (or red-1? red-2?)])
    ;; application
    (stx/app? t)
    (let [[left right] t
          ;; 1) try left reduction
          [left' lred?] (beta-step left)
          ;; 2) also try right reduction
          [right' rred?] (beta-step right)]
      (if (stx/lambda? left')
        ;; we have a redex
        [(beta-reduction [left' right']) true]
        ;; or we stay with an application
        [[left' right'] (or lred? rred?)]))
    ;; reference
    (stx/ref? t)
    (let [[def-name & args] t
          [args' red?] (beta-step-args args)
          t' (if red? (list* def-name args') t)]
      [t' red?])
    ;; ascriptions
    (stx/ascription? t)
    (let [[_ ty term] t
          [ty' tyred?] (beta-step ty)
          [term' termred?] (beta-step term)]
      [(list :latte-kernel.syntax/ascribe ty' term') (or tyred? termred?)])
    ;; other cases
    :else [t false]))

(defn beta-step-args
  "Apply the reduction strategy on the terms `ts` 
  in *\"parallel\"*. This is one place
  where many redexes can be found at once.
  This returns a pair composed of the rewritten
  terms and a flag telling if at least one reduction
  took place."
  [ts]
  (loop [ts ts, ts' [], red? false]
    (if (seq ts)
      (let [[t' red-1?] (beta-step (first ts))]
        (recur (rest ts) (conj ts' t') (or red? red-1?)))
      [ts' red?])))

(defn beta-red
  "Reduce term `t` according to the normalization strategy."
  [t]
  (let [[t' _] (beta-step t)]
    t'))


;;{
;; ## Delta-reduction (unfolding of definitions)
;;
;; If beta-reduction is at the heart of computing with
;; values in the lambda-calculus, the *delta-reduction* is
;; the corresponding principle for computing with *names*.
;;
;; From the point of view of calculability, names are not
;; needed: they don't add any expressive power to the
;; language. As such, they are not part of the theoretical
;; lambda-calculus. However in practice, names play a
;; central role in computation.
;;
;; From the point of view of LaTTe, the use of names
;; is important for two reasons:
;;
;;   1. Mathematical definitions and theorems need to be named so that they can
;;  be further referenced, that's a basic fact of mathematics.
;;
;;   2. Named computation can be performed much more efficiently than relying
;; on beta-reduction, this will be made clear later on.
;;
;; The rewrite rule of delta-reduction corresponds to the unfolding
;; of a pameterized definition, based on the substitution of the parameters
;; by actual arguments. The process is called *instantiation*.
;;}

(defn instantiate-def
  "Substitute in the `body` of a definition the parameters `params` 
  by the actual arguments `args`."
  [params body args]
  ;;(println "[instantiate-def] params=" params "body=" body "args=" args)
  (loop [args args, params params, sub {}]
    (if (seq args)
      (if (empty? params)
        (throw (ex-info "Not enough parameters (please report)" {:args args}))
        (recur (rest args) (rest params) (assoc sub (ffirst params) (first args))))
      (loop [params (reverse params), res body]
        (if (seq params)
          (recur (rest params) (list 'λ (first params) res))
          (stx/subst res sub))))))

;;{
;; Note that for the sake of efficiency, we do not unfold theorems (by their proof)
;; hence at the computational level a theorem is not equivalent to its proof, which
;; is of course a good thing because of *proof irrelevance*. However an error is
;; raised if one tries to reduce with a yet unproven theorem.
;;}

(defn delta-reduction
  "Apply a strategy of delta-reduction in definitional environment `def-env` and
  term `t`. If the flag `local?` is `true` the definition in only looked for
  in `def-env`. By default it is also looked for in the current namespace (in Clojure only).²"
  ([def-env t] (delta-reduction def-env t false))
  ([def-env t local?]
   ;; (println "[delta-reduction] t=" t)
   (if (not (stx/ref? t))
     (throw (ex-info "Cannot delta-reduce: not a reference term (please report)." {:term t}))
     (let [[name & args] t
           [status sdef]  (defenv/fetch-definition def-env name local?)]
       ;; (println "[delta-reduction] term=" t "def=" sdef)
       (if (= status :ko)
         [t false] ;; No error?  or (throw (ex-info "No such definition" {:term t :def-name name}))
         (if (> (count args) (:arity sdef))
           (throw (ex-info "Too many arguments to instantiate definition."
                           {:term t :def-name name :nb-params (count (:arity sdef)) :nb-args (count args)}))
           (cond
            (definition? sdef)
            ;; unfolding a defined term
            (if (:parsed-term sdef)
              [(instantiate-def (:params sdef) (:parsed-term sdef) args) true]
              (throw (ex-info "Cannot unfold term reference (please report)"
                              {:term t :def sdef})))
            (theorem? sdef)
            (if (:proof sdef)
              ;; unfolding works but yields very big terms
              ;; having a proof is like a certicate and thus
              ;; the theorem can now be considered as an abstraction, like
              ;; an axiom but with a proof...
              ;; [(instantiate-def (:params sdef) (:proof sdef) args) true]
              [t false]
              (throw (ex-info "Cannot use theorem with no proof." {:term t :theorem sdef})))
            (axiom? sdef) [t false]
            (special? sdef)
            (throw (ex-info "Specials must not exist at delta-reduction time (please report)"
                            {:term t :special sdef}))
            ;; XXX: before that, specials were handled by delta-reduction
            ;; (if (< (count args) (:arity sdef))
            ;;   (throw (ex-info "Not enough argument for special definition." { :term t :arity (:arity sdef)}))
            ;;   (let [term (apply (:special-fn sdef) def-env ctx args)]
            ;;     [term true]))
            :else (throw (ex-info "Not a Latte definition (please report)."
                                  {:term t :def sdef})))))))))

;;{
;; ### Delta-reduction strategy
;;
;; The strategy we adopt for delta-reduction is close to the one usesd
;; for beta-reduction. Of course we are not looking for beta but *delta-redexes*,
;;  i.e. *"call"* to definitions.
;;}

(declare delta-step-args)

(defn delta-step
  "Applies the strategy of *delta-reduction* on term `t` with definitional
 environment `def-env`. If the optional flag `local?` is `true` only the
  local environment is used, otherwise (the default case) the definitions
  are also searched in the current namespace (in Clojure only)."
  ([def-env t] (delta-step def-env t false))
  ([def-env t local?]
   ;; (println "[delta-step] t=" t)
   (cond
     ;; binder
     (stx/binder? t)
     (let [[binder [x ty] body] t
           ;; 1) try reduction in binding type
           [ty' red1?] (delta-step def-env ty local?)
           ;; 2) also try reduction in body
           [body' red2?] (delta-step def-env body local?)]
       [(list binder [x ty'] body') (or red1? red2?)])
     ;; application
     (stx/app? t)
     (let [[left right] t
           ;; 1) try left reduction
           [left' lred?] (delta-step def-env left local?)
           ;; 2) also try right reduction
           [right' rred?] (delta-step def-env right local?)]
       [[left' right'] (or lred? rred?)])
     ;; reference
     (stx/ref? t)
     (let [[def-name & args] t
           [args' red1?] (delta-step-args def-env args local?)
           t' (if red1? (list* def-name args') t)
           [t'' red2?] (delta-reduction def-env t' local?)]
       [t'' (or red1? red2?)])
     ;; ascription
     (stx/ascription? t)
     (let [[_ ty term] t
           [ty' tyred?] (delta-step def-env ty local?)
           [term' termred?] (delta-step def-env term local?)]
       [(list :latte-kernel.syntax/ascribe ty' term') (or tyred? termred?)])
     ;; other cases
     :else [t false])))

(defn delta-step-args
  "Applies the delta-reduction on the terms `ts`."
  [def-env ts local?]
  (loop [ts ts, ts' [], red? false]
    (if (seq ts)
      (let [[t' red-1?] (delta-step def-env (first ts) local?)]
        (recur (rest ts) (conj ts' t') (or red? red-1?)))
      [ts' red?])))


;;{
;; ## Reduction of specials
;;
;; The *specials* represent the place where one can benefit of the
;; full power of the host language, namely Clojure or Clojurescript,
;; to generate a term.
;; Because of the complex interactions between the normalization and
;; the type inference processes, there are strong restrictions imposed
;; on the rewrite engine for specials. The main restriction is that
;; a special must be removed before any further normalization using
;; the beta or delta rules.
;;
;; The rule of *special-reduction* (could be named *sigma-reduction*) is
;; basically calling the Clojure(script) function attached to the
;; special.
;;}

(defn special-reduction
  "Expand the term `t` as a special reference. If it is not
  a special it is not reduced. The function returns a pair `[t' red?]`
  with `t'` the expanded special, and `red?` is `true` iff an actual
  rewrite took place."
  [def-env ctx t]
  ;; (println "[special-reduction] t=" t)
  (if (not (stx/ref? t))
    (throw (ex-info "Cannot special-reduce: not a reference term." {:term t}))
    (let [[name & args] t
          [status sdef] (defenv/fetch-definition def-env name)]
      (if (= status :ko)
        [t false] ;; No error?  or (throw (ex-info "No such definition" {:term t :def-name name}))
        (if (special? sdef)
          (do
            ;; (println "[special-reduction] sdef=" sdef)
            (let [term (apply (:special-fn sdef) def-env ctx args)]
              [term true])) ;;)
          [t false])))))

;;{
;; ### The special-reduction strategy
;;
;; Once again we use the bottom-up "parallel" strategy of
;; reduction we used for both the beta and delta reduction cases.
;;}

(declare special-step-args)

(defn special-step
  "Applies the strategy of *special-reduction* on term `t` with definitional
  environment `def-env` and context `ctx`."
  [def-env ctx t]
  ;; (println "[delta-step] t=" t)
  (cond
    ;; binder
    (stx/binder? t)
    (let [[binder [x ty] body] t]
      ;; 1) try reduction in binding type
      (let [[ty' red1?] (special-step def-env ctx ty)
            ;; 2) try reduction in body
            [body' red2?] (special-step def-env ctx body)]
        [(list binder [x ty'] body') (or red1? red2?)]))
    ;; application
    (stx/app? t)
    (let [[left right] t
          ;; 1) try left reduction
          [left' lred?] (special-step def-env ctx left)
          ;; 2) try right reduction
          [right' rred?] (special-step def-env ctx right)]
      [[left' right'] (or lred? rred?)])
    ;; reference
    (stx/ref? t)
    (let [[def-name & args] t
          [args' red1?] (special-step-args def-env ctx args)
          t' (if red1? (list* def-name args') t)
          [t'' red2?] (special-reduction def-env ctx t')]
      [t'' (or red1? red2?)])
    ;; ascription
    (stx/ascription? t)
    (let [[_ ty term] t
          [ty' tyred?] (special-step def-env ctx ty)
          [term' termred?] (special-step def-env ctx term)]
      [(list :latte-kernel.syntax/ascribe ty' term') (or tyred? termred?)])
    ;; other cases
    :else [t false]))

(defn special-step-args
  "Applies the delta-reduction on the terms `ts`."
  [def-env ctx ts]
  (loop [ts ts, ts' [], red? false]
    (if (seq ts)
      (let [[t' red1?] (special-step def-env ctx (first ts))]
        (recur (rest ts) (conj ts' t') (or red? red1?)))
      [ts' red?])))

;;{
;; ## Normalization
;;
;; We finally define a few normalization functions:
;;   - normalize specials only: [[special-normalize]]
;;   - normalize using beta-reduction only: [[beta-normalize]]
;;   - normalize using delta-reduction only: [[delta-normalize]]
;;   - normalize using delta-reduction with the local environment only: [[delta-normalize-local]]
;;   - generic normalization: [[beta-delta-special-normalize]]
;;}

(defn special-normalize
  "Normalize term `t` for special-reduction."
  [def-env ctx t]
  (let [[t' red?] (special-step def-env ctx t)]
    (if red?
      (recur def-env ctx t')
      t')))

(defn beta-normalize
  "Normalize term `t` for beta-reduction."
  [t]
  (let [[t' red?] (beta-step t)]
    (if red?
      (recur t')
      t')))

(defn delta-normalize
  "Normalize term `t` for delta-reduction."
  [def-env t]
  (let [[t' red?] (delta-step def-env t)]
    (if red?
      (recur def-env t')
      t')))

(defn delta-normalize-local
  "Normalize term `t` for delta-reduction using only
  environment `def-env` (and *not* the current namespace)."
  [def-env t]
  (let [[t' red?] (delta-step def-env t true)]
    (if red?
      (recur def-env t')
      t')))

;;{
;; The heart of the general normalization process is
;; the following function. It orders the strategies
;; in the following way:
;;   1. apply special-reduction first,
;;   2. then apply delta-reduction
;;   3. then apply beta-reduction
;;   4. try again the whole process if the term was rewritten
;;      or just return the result.
;;
;; The LaTTe *normal forms* are defined by this function, i.e.
;; these are the terms for which the function acts as an identity.
;; This is a formal definition, but its mathematical properties
;; are not easy to derive from the code. However it has been
;; thoroughly tested. It is also *safe* in the sense that at worst
;; it will lead to too many distinctions, but there is no risk
;; of confusion.
;;
;; **Remark**: some optimizations could be performed here, but
;; we found out that even small change in this definition
;; could easily lead to dramatic effects, so we are very
;; conservative in this part of the kernel.
;;}

(defn beta-delta-special-normalize
  "Apply the general normalization strategy of LaTTe on term `t`.
  The result is defined as *the normal form* of `t`."
  [def-env ctx t]
  ;;(println "[beta-delta-special-normalize]: t=" t)
  (let [[t' spec-red?] (special-step def-env ctx t)]
    (if spec-red?
      (do ;;(println "  [special] t ==> " t')
          (recur def-env ctx t'))
      (let [[t' delta-red?] (delta-step def-env t)]
        (if delta-red?
          (do ;;(println "  [delta] t ==> " t')
              (recur def-env ctx t'))
          (let [[t' beta-red?] (beta-step t)]
            (if beta-red?
              (do ;;(println "  [beta] t ==> " t')
                  (recur def-env ctx t'))
              t')))))))

;;{
;; The following is the main user-level function for normalization.
;;}

(defn normalize
  "Normalize term `t` in (optional) environment `def-env` and (optional) context `ctx`.
  The result is *the normal form* of `t`."
  ([t] (normalize {} [] t))
  ([def-env t] (normalize def-env [] t))
  ([def-env ctx t] (beta-delta-special-normalize def-env ctx t)))

;;{
;; ## Beta-equivalence
;;
;; The main objective of having a normalization algorithm is to be
;; able to compare terms for a dedicated notion of equivalence.
;; In the lambda-calculus this is generally called *beta-equivalence*
;; and we will convey the same name here. However it is clear that
;; we also mean equivalent for our general normalization procedure
;; (involving delta and special reduction also).
;; Note also that the resulting normal forms are compared for
;; alpha-equivalence so that bound variables do not get in the way.
;;}

(defn beta-eq?
  "Are terms `t1` and `t2` equivalent, i.e. with the
same normal form (up-to alpha-convertion)?"
  ([t1 t2]
   (let [t1' (normalize t1)
         t2' (normalize t2)]
     (stx/alpha-eq? t1' t2')))
  ([def-env t1 t2]
   (let [t1' (normalize def-env t1)
         t2' (normalize def-env t2)]
     (stx/alpha-eq? t1' t2')))
  ([def-env ctx t1 t2]
   (let [t1' (normalize def-env ctx t1)
         t2' (normalize def-env ctx t2)]
     (stx/alpha-eq? t1' t2'))))
