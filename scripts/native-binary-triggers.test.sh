#!/usr/bin/env bash
# Test for the job conditions of .github/workflows/native-binary.yml (run by
# .github/workflows/ci.yml). Offline: GitHub Actions cannot run locally, so
# this reads the two workflow files and decides, for each trigger, which jobs
# would run, with the two rules GitHub documents:
#
# - a job runs when its `if` is true;
# - an `if` without a status function (success(), always(), cancelled(),
#   failure()) gets an implicit success(), which is false as soon as one of
#   the job's `needs` did not succeed, a skipped one included.
#
# What it guards (issue #61): a pull request runs the full suite once (in
# ci.yml, never again in the `test` job here) and still gets the native build;
# a release still runs the suite before the build; a red suite never builds
# or deploys; a redeploy only deploys; a pull request never deploys.
#
# Only job-level conditions are modelled, not the `if` of a step. An
# expression the model does not know fails the test instead of guessing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ruby - "$ROOT/.github/workflows/native-binary.yml" "$ROOT/.github/workflows/ci.yml" <<'RUBY'
require 'yaml'

STATUS_FUNCTIONS = %w[success failure cancelled always].freeze

# The subset of GitHub's expression language the job conditions use.
class Expression
  TOKEN = /\s*(\|\||&&|==|!=|!|\(|\)|'(?:[^']|'')*'|[A-Za-z_][\w.-]*)/

  def initialize(text, context, status)
    body = text.to_s.strip.sub(/\A\$\{\{/, '').sub(/\}\}\z/, '')
    @tokens = []
    rest = body
    until rest.strip.empty?
      match = TOKEN.match(rest)
      raise "cannot read expression at: #{rest}" unless match && match.begin(0).zero?
      @tokens << match[1]
      rest = match.post_match
    end
    @uses_status_function =
      @tokens.each_cons(2).any? { |name, paren| STATUS_FUNCTIONS.include?(name) && paren == '(' }
    @context = context
    @status = status
  end

  def uses_status_function?
    @uses_status_function
  end

  def true?
    value = either
    raise "unexpected '#{@tokens.first}'" unless @tokens.empty?
    truthy(value)
  end

  private

  def truthy(value)
    !(value.nil? || value == false || value == '')
  end

  def either
    value = both
    value = [truthy(value), truthy(both)].any? while take('||')
    value
  end

  def both
    value = equality
    value = [truthy(value), truthy(equality)].all? while take('&&')
    value
  end

  def equality
    left = negation
    if take('==') then same?(left, negation)
    elsif take('!=') then !same?(left, negation)
    else left
    end
  end

  # null and '' compare equal, and strings ignore case, as on GitHub.
  def same?(left, right)
    left.to_s.casecmp?(right.to_s)
  end

  def negation
    take('!') ? !truthy(negation) : primary
  end

  def primary
    token = @tokens.shift or raise 'expression ends too early'
    if token == '('
      value = either
      take(')') or raise "missing ')'"
      value
    elsif token.start_with?("'")
      token[1..-2].gsub("''", "'")
    elsif take('(')
      take(')') or raise "#{token}() takes no argument here"
      @status.fetch(token) { raise "unknown function #{token}()" }
    else
      @context.fetch(token) { raise "unknown context #{token}" }
    end
  end

  def take(token)
    @tokens.first == token && @tokens.shift
  end
end

# Which jobs of a workflow run, in the order they are decided.
#   event:     github.event_name
#   sha256:    the workflow_dispatch input (nil when the event has no inputs)
#   failing:   a job that fails when it runs
#   cancelled: the run is cancelled before the jobs after `test` are decided
def jobs_that_run(workflow, event:, sha256: nil, failing: nil, cancelled: false)
  jobs = workflow.fetch('jobs')
  results = {}
  until results.size == jobs.size
    name, job = jobs.find { |n, j| !results.key?(n) && Array(j['needs']).all? { |need| results.key?(need) } }
    raise 'the jobs need each other in a circle' unless name
    needs = Array(job['needs'])
    stopped = cancelled && !needs.empty?
    context = { 'github.event_name' => event, 'inputs.sha256' => sha256 }
    needs.each { |need| context["needs.#{need}.result"] = results.fetch(need) }
    succeeded = !stopped && needs.all? { |need| results.fetch(need) == 'success' }
    status = {
      'success' => succeeded,
      'failure' => !stopped && needs.any? { |need| results.fetch(need) == 'failure' },
      'cancelled' => stopped,
      'always' => true
    }
    condition = Expression.new(job.fetch('if', 'success()'), context, status)
    runs = condition.true? && (condition.uses_status_function? || succeeded)
    results[name] = if !runs then 'skipped'
                    elsif name == failing then 'failure'
                    else 'success'
                    end
  end
  results.reject { |_, result| result == 'skipped' }.keys
end

# A step that runs the full suite: a Maven package that does not skip tests.
def suite_steps(workflow, job_names)
  job_names.flat_map { |name| workflow.fetch('jobs').fetch(name).fetch('steps') }
           .map { |step| step['run'].to_s }
           .select { |run| run =~ /mvnw\b.*\bpackage\b/m && run !~ /-DskipTests/ }
end

# YAML 1.1 reads the key `on` as the boolean true.
def triggers(workflow)
  workflow['on'] || workflow[true]
end

native = YAML.load_file(ARGV.fetch(0))
ci = YAML.load_file(ARGV.fetch(1))
HASH = 'a' * 64

failures = 0
check = lambda do |title, expected, actual|
  if expected == actual
    puts "PASS  #{title}"
  else
    puts "FAIL  #{title}: expected #{expected.inspect}, got #{actual.inspect}"
    failures += 1
  end
end

# --- release: published ------------------------------------------------------
check.call('release: suite, then build, then deploy',
           %w[test build deploy], jobs_that_run(native, event: 'release'))
check.call('release with a red suite: no build, no deploy',
           %w[test], jobs_that_run(native, event: 'release', failing: 'test'))
check.call('release with a red build: no deploy',
           %w[test build], jobs_that_run(native, event: 'release', failing: 'build'))
check.call('release cancelled after the suite: no build, no deploy',
           %w[test], jobs_that_run(native, event: 'release', cancelled: true))
check.call('release: the suite runs once, in the job the build needs',
           [1, 'test'],
           [suite_steps(native, %w[test]).size, native.fetch('jobs').fetch('build')['needs']])

# --- workflow_dispatch -------------------------------------------------------
check.call('dispatch without sha256: suite, then build, then deploy',
           %w[test build deploy], jobs_that_run(native, event: 'workflow_dispatch', sha256: ''))
check.call('dispatch without sha256 and a red suite: no build, no deploy',
           %w[test], jobs_that_run(native, event: 'workflow_dispatch', sha256: '', failing: 'test'))
check.call('dispatch with sha256 (redeploy): only the deploy',
           %w[deploy], jobs_that_run(native, event: 'workflow_dispatch', sha256: HASH))

# --- pull_request ------------------------------------------------------------
on_pull_request = jobs_that_run(native, event: 'pull_request')
check.call('pull request: the native build without the suite job, and no deploy',
           %w[build], on_pull_request)
check.call('pull request with a red build: still no deploy',
           %w[build], jobs_that_run(native, event: 'pull_request', failing: 'build'))
check.call('pull request: ci.yml runs on every one (no path filter)',
           [true, []],
           [triggers(ci).key?('pull_request'),
            (triggers(ci)['pull_request'] || {}).keys & %w[paths paths-ignore branches branches-ignore]])
check.call('pull request: the full suite runs once across ci.yml and native-binary.yml',
           1,
           suite_steps(ci, jobs_that_run(ci, event: 'pull_request')).size +
           suite_steps(native, on_pull_request).size)

exit(failures.zero? ? 0 : 1)
RUBY
