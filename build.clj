(ns build
  "Build the Athens server uberjar.
   From https://clojure.org/guides/tools_build#_compiled_uberjar_application_build"
  (:require
    [clojure.tools.build.api :as b]))


(def version (format "1.0.0-alpha.rtc.xx"))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file "target/athens-lan-party-standalone.jar")
(def src-dirs ["src/clj" "src/cljc"])


(defn uber
  [_]
  (b/delete {:path "target"})
  (b/copy-dir {:src-dirs src-dirs
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs src-dirs
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'athens.self-hosted.core}))
