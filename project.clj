(defproject com.aphyr/mastodon-utils "0.1.0-SNAPSHOT"
  :description "Utilities for working with Mastodon"
  :url "https://github.com/aphyr/mastodon-utils"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[cheshire "5.11.0"]
                 [clj-http "3.12.3"]
                 [dom-top "1.0.8"]
                 [org.clojure/clojure "1.11.0"]
                 [org.clojure/tools.cli "1.0.214"]
                 [org.clojure/tools.logging "1.2.4"]
                 [slingshot "0.12.2"]]
  :repl-options {:init-ns com.aphyr.mastodon-utils})
