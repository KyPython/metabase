(ns build
  (:require
   [build.licenses :as license]
   [build.python :as build.python]
   [build.uberjar :as uberjar]
   [build.version-properties :as version-properties]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [environ.core :as env]
   [flatland.ordered.map :as ordered-map]
   [metabuild-common.core :as u]))

(set! *warn-on-reflection* true)

(defn- edition-from-env-var []
  (case (env/env :mb-edition)
    "oss" :oss
    "ee"  :ee
    nil   :oss))

(defn- build-frontend!
  "Build the frontend. `before-js` runs after the ClojureScript build and before the JavaScript build, which is the
  first part that reads the output of `:translations`."
  [edition {:keys [before-js]}]
  {:pre [(#{:oss :ee} edition)]}
  (let [mb-edition (case edition
                     :ee "ee"
                     :oss "oss")
        env        {"PATH"                (env/env :path)
                    "HOME"                (env/env :user-home)
                    "WEBPACK_BUNDLE"      "production"
                    "MB_EDITION"          mb-edition
                    "EMIT_BUNDLE_STATS"   (or (env/env :emit-bundle-stats) "false")
                    "INSTRUMENT_COVERAGE" (or (env/env :instrument-coverage) "false")}]
    (u/step (format "Build frontend with MB_EDITION=%s" mb-edition)
      (when-not (env/env :ci)
        (u/step "Run 'bun install' to download JavaScript dependencies"
          (u/sh {:dir u/project-root-directory} "bun" "install")))
      ;; `bun run build-release`, in two parts
      (u/step "Build ClojureScript"
        (u/sh {:dir u/project-root-directory, :env env} "bun" "run" "build-release:cljs"))
      (when before-js
        (before-js))
      (u/step "Build frontend"
        (u/sh {:dir u/project-root-directory, :env env} "bun" "run" "build-release:js"))
      (u/step "Build static viz"
        (u/sh {:dir u/project-root-directory
               :env {"PATH"       (env/env :path)
                     "HOME"       (env/env :user-home)
                     "WEBPACK_BUNDLE"   "production"
                     "MB_EDITION" mb-edition}}
              "bun" "run" "build-release:static-viz"))
      (u/announce "Frontend built successfully."))))

(defn- build-licenses!
  [edition]
  {:pre [(#{:oss :ee} edition)]}
  (when-not (= (env/env :skip-licenses) "true")
    (u/step "Generate backend license information from jar files"
      (let [basis                     (b/create-basis {:project (u/filename u/project-root-directory "deps.edn")})
            output-filename           (u/filename u/project-root-directory
                                                  "resources"
                                                  "license-backend-third-party.txt")
            {:keys [without-license]} (license/generate {:basis           basis
                                                         :backfill        (edn/read-string
                                                                           (slurp (io/resource "overrides.edn")))
                                                         :output-filename output-filename
                                                         :report?         false})]
        (when (seq without-license)
          (run! (comp (partial u/error "Missing License: %s") first)
                without-license))
        (u/announce "License information generated at %s" output-filename)))
    (u/step "Run `bun run generate-license-disclaimer`"
      (u/sh {:dir u/project-root-directory}
            "bun" "run" "generate-license-disclaimer"))))

(defn- build-translations! []
  (u/sh {:dir u/project-root-directory} "bin/i18n/build-translation-resources"))

(defn- build-uberjar!
  "Build the uberjar. If `basis` is passed, the sources were already compiled in the background (see
  [[start-background-compile!]]), so only package them."
  [edition basis]
  {:pre [(#{:oss :ee} edition)]}
  (u/delete-file-if-exists! uberjar/uberjar-filename)
  (u/step (format "Build uberjar with profile %s" edition)
    (if basis
      (uberjar/package! basis)
      (uberjar/uberjar {:edition edition}))
    (u/assert-file-exists uberjar/uberjar-filename)
    (u/announce "Uberjar built successfully.")))

(def ^:private all-steps
  "These build steps are run in order during the build process."
  (ordered-map/ordered-map
   :version      (fn [{:keys [edition version]}]
                   (version-properties/generate-version-properties-file! edition version))
   :translations (fn [_]
                   (build-translations!))
   :frontend     (fn [{:keys [edition before-js]}]
                   (build-frontend! edition {:before-js before-js}))
   :licenses     (fn [{:keys [edition]}]
                   (build-licenses! edition))
   #_#_:drivers      (fn [{:keys [edition]}]
                       (build-drivers/build-drivers! edition))
   :python       (fn [{:keys [edition]}]
                   (build.python/build-python-deps! edition))
   :uberjar      (fn [{:keys [edition basis]}]
                   (build-uberjar! edition basis))))

(defn- github-actions? []
  (= (System/getenv "GITHUB_ACTIONS") "true"))

(defn- do-with-log-group
  "Run `thunk`, wrapping its output in a collapsible GitHub Actions log group named `title` when running on GitHub
  Actions. GitHub Actions doesn't support nested groups, so only use this for top-level build steps."
  [title thunk]
  (if-not (github-actions?)
    (thunk)
    ;; Workflow commands must start at the beginning of the line, so don't use `u/safe-println`, which indents.
    (do (locking println (println (str "::group::" title)))
        (try
          (thunk)
          (finally
            (locking println (println "::endgroup::")))))))

(defn- start-background-compile!
  "Start AOT-compiling `edition` on another thread, so it runs while the frontend builds. Its output is held back so
  it doesn't interleave with the output of the steps running meanwhile; [[await-background-compile!]] prints it.

  This process's output goes to an in-memory buffer. The compiler runs in a child JVM, so its output goes to files
  under `target/`; those survive if the job is killed before the output is printed."
  [edition]
  (let [buffer (java.io.StringWriter.)
        logs   {:out-file (str u/project-root-directory "/target/compile.out.log")
                :err-file (str u/project-root-directory "/target/compile.err.log")}]
    (io/make-parents (:out-file logs))
    {:buffer buffer
     :logs   logs
     :result (future
               (binding [*out* buffer]
                 (let [basis (uberjar/compile! edition logs)]
                   {:basis basis, :finished-at (System/nanoTime)})))}))

(defn- print-background-compile-output! [{:keys [buffer logs]}]
  (locking println
    (print (str buffer))
    (flush))
  (doseq [file  [(:out-file logs) (:err-file logs)]
          :when (.exists (io/file file))
          line  (str/split-lines (slurp file))]
    (u/safe-println line)))

(defn- await-background-compile!
  "Wait for the compile started by [[start-background-compile!]], print its output as one block, and return the basis
  to package. If the compile failed, print its output outside of a log group, so the error is visible, and rethrow."
  [{:keys [result] :as compile}]
  (let [waiting-since (System/nanoTime)
        outcome       (try
                        {:value @result}
                        (catch java.util.concurrent.ExecutionException e
                          {:error (or (.getCause e) e)}))
        ms            (fn [nanos] (long (/ nanos 1e6)))]
    (if-let [error (:error outcome)]
      (do (print-background-compile-output! compile)
          (throw error))
      (let [{:keys [basis finished-at]} (:value outcome)
            waited-ms                   (ms (- (System/nanoTime) waiting-since))]
        (do-with-log-group (if (pos? waited-ms)
                             (format "compile (ran in the background; uberjar waited %d ms for it)" waited-ms)
                             (format "compile (ran in the background; finished %d ms before uberjar needed it)"
                                     (ms (- waiting-since finished-at))))
                           #(print-background-compile-output! compile))
        basis))))

(defn- start-background-translations!
  "Build the translations on another thread, so the ClojureScript build doesn't wait for them, and call `then` once
  they are built. Their output is held back until [[await-background-translations!]]."
  [then]
  (let [buffer (java.io.StringWriter.)]
    {:buffer buffer
     :result (future
               (let [timer (u/start-timer)]
                 (binding [*out* buffer]
                   (build-translations!))
                 (then)
                 (u/since-ms timer)))}))

(defn- await-background-translations!
  "Wait for the translations started by [[start-background-translations!]] and print their output. A failure rethrows,
  and its message carries the command's output."
  [{:keys [buffer result]}]
  (let [waiting-since (u/start-timer)
        ran-ms        (try
                        @result
                        (catch java.util.concurrent.ExecutionException e
                          (throw (or (.getCause e) e))))]
    (u/announce "Translations ran in the background in %d ms; the frontend waited %d ms for them."
                ran-ms (u/since-ms waiting-since))
    (locking println
      (print (str buffer))
      (flush))))

(defn build!
  "Programmatic entrypoint."
  ([]
   (build! nil))

  ([{:keys [version edition steps]
     :or   {edition (edition-from-env-var)
            steps   (keys all-steps)}}]
   (let [version  (or version
                      (version-properties/current-snapshot-version edition))
         timer    (u/start-timer)
         ;; AOT compilation doesn't need the frontend, so it starts with the frontend step and `:uberjar` waits for it.
         ;; Not any earlier: `:translations` rewrites `resources/locales.clj`, which the compile reads while it looks for
         ;; namespaces, and a half-written file fails the build.
         step-set           (set (map u/parse-as-keyword steps))
         background-compile (when (every? step-set [:frontend :uberjar])
                              (delay (start-background-compile! edition)))
         ;; Only the JavaScript build reads the translations, so the ClojureScript build doesn't wait for them. The
         ;; compile starts once they are done, for the reason above.
         background-translations (when (every? step-set [:translations :frontend])
                                   (delay (start-background-translations!
                                           #(some-> background-compile force))))]
     (u/step (format "Running build steps for %s version %s: %s"
                     (case edition
                       :oss "Community (OSS) Edition"
                       :ee  "Enterprise Edition")
                     version
                     (str/join ", " (map name steps)))
       (doseq [step-name steps
               :let      [step-fn (or (get all-steps (u/parse-as-keyword step-name))
                                      (throw (ex-info (format "Invalid step: %s" step-name)
                                                      {:step        step-name
                                                       :valid-steps (keys all-steps)})))]]
         (let [step  (u/parse-as-keyword step-name)
               basis (case step
                       :frontend (do (when-not background-translations
                                       (some-> background-compile force))
                                     nil)
                       :uberjar  (some-> background-compile force await-background-compile!)
                       nil)]
           (do-with-log-group (name step-name)
                              #(if (and (= step :translations) background-translations)
                                 (do (force background-translations)
                                     (u/announce "Building translations in the background."))
                                 (step-fn {:version   version
                                           :edition   edition
                                           :basis     basis
                                           :before-js (when background-translations
                                                        (fn []
                                                          (await-background-translations! (force background-translations))
                                                          (some-> background-compile force)))}))))
         (u/announce "Did %s in %d ms." step-name (u/since-ms timer)))
       (u/announce "All build steps finished.")))))

(defn build-cli
  "CLI entrypoint. This is just a slim wrapper around [[build!]] that exists with a nonzero status if an exception is
  thrown."
  [options]
  (u/exit-when-finished-nonzero-on-exception
    (build! options)))

(defn list-without-license
  "From the command line:

    clojure -X:build:build/list-without-license"
  [_options]
  (let [[classpath]               (u/sh {:dir    u/project-root-directory
                                         :quiet? true}
                                        "clojure" "-A:ee" "-Spath")
        classpath-entries         (license/jar-entries classpath)
        {:keys [without-license]} (license/process*
                                   {:classpath-entries classpath-entries
                                    :backfill          (edn/read-string
                                                        (slurp (io/resource "overrides.edn")))})]
    (if (seq without-license)
      (run! (comp (partial u/error "Missing License: %s") first)
            without-license)
      (u/announce "All dependencies have licenses"))
    (shutdown-agents)
    (System/exit (if (seq without-license) 1 0))))
