# Compose names its volumes "<project>_<volume>", and defaults the project to this directory.
project := file_name(justfile_directory())

default:
    just --list

# Run the test suite
test:
    clojure -T:build test

# Lint the source with clj-kondo
lint:
    clojure -M:clj-kondo --lint src test dev

# Run the CI pipeline (tests + build JAR)
ci:
    clojure -T:build ci

# Install the JAR locally (requires `just ci` first)
install:
    clojure -T:build install

# Deploy the JAR to Clojars (requires `just ci` first)
deploy:
    clojure -T:build deploy

# Start a REPL
repl:
    clojure -M:dev:test

# Start Docker compose
up: 
    docker compose up -d 

# Stop Docker compose
down:
    docker compose down

# Wipe the Resonate database and start over (Prometheus and Grafana data are untouched)
[confirm("Delete the Resonate database (all promises and tasks)? [y/N]")]
reset-db:
    docker compose down
    docker volume rm -f {{project}}_postgres-data
    docker compose up -d