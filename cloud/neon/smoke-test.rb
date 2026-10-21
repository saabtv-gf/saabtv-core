# Smoke-tests the deployed API with isolated, generated QA users.
# Never outputs passwords, cookies, JWTs or real user data.
require 'net/http'
require 'json'
require 'securerandom'
require 'digest'
$stdout.sync = true

AUTH = 'https://ep-gentle-voice-azd9if2p.neonauth.c-3.ap-southeast-1.aws.neon.tech/neondb/auth'
DATA = 'https://ep-gentle-voice-azd9if2p.apirest.c-3.ap-southeast-1.aws.neon.tech/neondb/rest/v1'
def call_api(base, path, body: nil, raw_body: nil, method: nil, cookie: nil, token: nil)
  uri = URI("#{base}/#{path}")
  verb = (method || (body || raw_body ? :post : :get)).to_sym
  request_type = {get: Net::HTTP::Get, post: Net::HTTP::Post,
                  patch: Net::HTTP::Patch, delete: Net::HTTP::Delete}.fetch(verb)
  req = request_type.new(uri)
  req['Content-Type'] = 'application/json'
  req['Origin'] = URI(AUTH).then { |value| "#{value.scheme}://#{value.host}" } if base == AUTH
  req['Cookie'] = cookie if cookie
  req['Authorization'] = "Bearer #{token}" if token
  req.body = raw_body || JSON.generate(body) if body || raw_body
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 15, read_timeout: 60) { |http| http.request(req) }
  json = JSON.parse(res.body) rescue {}
  [res.code.to_i, json, res.get_fields('set-cookie') || []]
end
def v3_operation(device_id:, sequence:, seed:, ciphertext: 'QA_TEST_V3_CIPHERTEXT')
  {
    operation_id: SecureRandom.uuid,
    device_id: device_id,
    profile_id: 'qa-profile',
    entity_key: Digest::SHA256.hexdigest(seed),
    entity_type: 'profile_runtime',
    operation: 'UPSERT',
    entity_revision: 1,
    sequence: sequence,
    ciphertext: ciphertext
  }
end
def assert(condition, message)
  raise message unless condition
  puts "PASS #{message}"
end

unless ENV['SAAB_QA_CREATE_USERS'] == '1'
  abort 'Refusing to create QA accounts. Set SAAB_QA_CREATE_USERS=1 only after authorizing disposable-account creation and cleanup.'
end
if (ENV['SAAB_QA_VALIDATE_413'] == '1' || ENV['SAAB_QA_VALIDATE_PUSH_BYTES'] == '1') &&
    ENV['SAAB_QA_VALIDATE_006'] != '1'
  abort 'SAAB_QA_VALIDATE_413 and SAAB_QA_VALIDATE_PUSH_BYTES require SAAB_QA_VALIDATE_006=1.'
end

users = []
begin
  code, anon = call_api(AUTH, 'token/anonymous')
  assert(code == 200 && anon['token'], 'anonymous token issued')
  suffix = SecureRandom.hex(6)
  ["qa_#{suffix}_a", "qa_#{suffix}_b"].each_with_index do |name, index|
    email = "#{name}@accounts.saabtv.invalid"
    password = index == 0 ? 'Ab1!xy' : "Q#{SecureRandom.hex(12)}!x7"
    code, result = call_api(DATA, 'rpc/saabtv_username_available', body: {requested_username: name}, token: anon['token'])
    assert(code == 200 && result == true, 'new username available')
    code, result, cookies = call_api(AUTH, 'sign-up/email', body: {email: email, password: password, name: name})
    if code == 400 && result['code'] == 'PASSWORD_TOO_SHORT'
      puts 'Neon minimum password length exceeds 6 characters.'
      password = "Q#{SecureRandom.hex(12)}!x7"
      code, result, cookies = call_api(AUTH, 'sign-up/email', body: {email: email, password: password, name: name})
    end
    cookie = cookies.map { |value| value.split(';').first }.select { |value| value.include?('auth.session_token=') }.join('; ')
    users << {name: name, email: email, password: password, id: result['user']['id'], cookie: cookie} if result['user']
    assert(code == 200 && result['user'] && result['token'], "signup succeeds (status #{code}, code #{result['code']})")
    assert(!cookie.empty?, 'session cookie returned')
    puts "Cookie name: #{cookie.split('=').first}"
    code, session = call_api(AUTH, 'get-session', cookie: cookie)
    assert(code == 200 && session.dig('user', 'id') == users.last[:id], 'session validated')
    code, jwt = call_api(AUTH, 'token', cookie: cookie)
    assert(code == 200 && jwt['token'], 'account JWT issued')
    users.last[:jwt] = jwt['token']
    code, available = call_api(DATA, 'rpc/saabtv_username_available', body: {requested_username: name}, token: anon['token'])
    assert(code == 200 && available == false, 'existing username unavailable')
  end
  begin
  a, b = users
  code, saved = call_api(DATA, 'rpc/saabtv_save_account', body: {expected_revision: 0, encrypted_state: 'QA_TEST_CIPHERTEXT'}, token: a[:jwt])
  puts "Snapshot response: HTTP #{code}, code=#{saved['code']}, message=#{saved['message']}, result=#{saved.slice('revision','conflict')}"
  assert(code == 200 && saved['revision'] == 1 && saved['conflict'] == false, 'first account snapshot saved')
  code, saved = call_api(DATA, 'rpc/saabtv_save_account', body: {expected_revision: 0, encrypted_state: 'STALE_QA_WRITE'}, token: a[:jwt])
  assert(code == 200 && saved['conflict'] == true, 'stale write rejected')
  code, saved = call_api(DATA, 'rpc/saabtv_save_account', body: {expected_revision: 1, encrypted_state: 'QA_TEST_UPDATED'}, token: a[:jwt])
  assert(code == 200 && saved['revision'] == 2 && saved['conflict'] == false, 'new revision saved')
  code, rows = call_api(DATA, 'saabtv_account_state?select=user_id,revision,ciphertext', token: a[:jwt])
  assert(code == 200 && rows.length == 1 && rows.first['user_id'] == a[:id] && rows.first['ciphertext'] == 'QA_TEST_UPDATED', 'owner can retrieve own snapshot')
  code, rows = call_api(DATA, 'saabtv_account_state?select=user_id,revision,ciphertext', token: b[:jwt])
  assert(code == 200 && rows == [], 'second account cannot read first account data')
  code, rows = call_api(DATA, "saabtv_account_state?user_id=eq.#{a[:id]}&select=user_id", token: b[:jwt])
  assert(code == 200 && rows == [], 'explicit cross-account query blocked')
  code, rows = call_api(DATA, 'saabtv_account_state?select=user_id', token: anon['token'])
  assert(code == 403 || code == 401, 'anonymous account-state access denied')
  code, login = call_api(AUTH, 'sign-in/email', body: {email: a[:email], password: a[:password]})
  assert(code == 200 && login.dig('user', 'id') == a[:id], 'password login returns the same stable account ID')

  device_id = SecureRandom.uuid
  operation = v3_operation(device_id: device_id, sequence: 1, seed: "#{a[:id]}:v3-smoke")
  code, pushed = call_api(DATA, 'rpc/saabtv_sync_v3_push', body: {operations: [operation]}, token: a[:jwt])
  assert(code == 200 && pushed['accepted_operation_ids'] == [operation[:operation_id]], 'v3 encrypted state push accepted')
  code, pulled = call_api(DATA, 'rpc/saabtv_sync_v3_pull', body: {after_sequence: 0, max_results: 10}, token: a[:jwt])
  assert(code == 200 && pulled.dig('operations')&.any? { |row| row['operation_id'] == operation[:operation_id] }, 'v3 owner can pull its state')
  code, pulled = call_api(DATA, 'rpc/saabtv_sync_v3_pull', body: {after_sequence: 0, max_results: 10}, token: b[:jwt])
  assert(code == 200 && pulled['operations'] == [], 'v3 state is isolated from the second account')

  if ENV['SAAB_QA_VALIDATE_006'] == '1'
    code, denied = call_api(DATA, 'saabtv_account_state', method: :post,
      body: {user_id: a[:id], revision: 99, ciphertext: 'DIRECT_WRITE_MUST_FAIL'}, token: a[:jwt])
    assert([401, 403].include?(code), "migration 006 denies direct snapshot writes (HTTP #{code}, #{denied['code']})")
    object_id = SecureRandom.uuid
    code, denied = call_api(DATA, 'saabtv_account_objects', method: :post,
      body: {user_id: a[:id], object_id: object_id, ciphertext: 'DIRECT_WRITE_MUST_FAIL'}, token: a[:jwt])
    assert([401, 403].include?(code), "migration 006 denies direct component writes (HTTP #{code}, #{denied['code']})")
    code, denied = call_api(DATA, "saabtv_account_objects?object_id=eq.#{object_id}", method: :delete, token: a[:jwt])
    assert([401, 403].include?(code), "migration 006 denies direct component deletes (HTTP #{code}, #{denied['code']})")

    if ENV['SAAB_QA_VALIDATE_PUSH_BYTES'] == '1'
      byte_device = SecureRandom.uuid
      byte_payload = 'x' * 400_000
      first_batch = 1.upto(34).map do |sequence|
        v3_operation(device_id: byte_device, sequence: sequence,
          seed: "#{a[:id]}:bytes:first:#{sequence}", ciphertext: byte_payload)
      end
      first_body = JSON.generate(operations: first_batch)
      assert(first_body.bytesize < 20 * 1024 * 1024, 'aggregate-byte fixture first request stays below the 20 MiB single-request cap')
      code, result = call_api(DATA, 'rpc/saabtv_sync_v3_push', raw_body: first_body, token: a[:jwt])
      assert(code == 200, "migration 006 accepts the first sub-cap byte batch (HTTP #{code}, #{result['code']})")
      first_body = nil
      first_batch = nil

      second_batch = 35.upto(68).map do |sequence|
        v3_operation(device_id: byte_device, sequence: sequence,
          seed: "#{a[:id]}:bytes:second:#{sequence}", ciphertext: byte_payload)
      end
      second_body = JSON.generate(operations: second_batch)
      assert(second_body.bytesize < 20 * 1024 * 1024, 'aggregate-byte fixture second request stays below the 20 MiB single-request cap')
      code, result = call_api(DATA, 'rpc/saabtv_sync_v3_push', raw_body: second_body, token: a[:jwt])
      assert(code == 429 && result['code'] == 'PT429', 'migration 006 enforces aggregate push-byte quota independently of the single-request cap')
    end

    # User B has not pushed before this check; use distinct entity keys but one
    # device sequence so each successful call exercises the same quota row.
    quota_started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    quota_device = SecureRandom.uuid
    push_throttled = false
    1.upto(31) do |sequence|
      row = v3_operation(device_id: quota_device, sequence: sequence, seed: "#{b[:id]}:quota:#{sequence}")
      code, result = call_api(DATA, 'rpc/saabtv_sync_v3_push', body: {operations: [row]}, token: b[:jwt])
      if code == 429
        assert(result['code'] == 'PT429', 'migration 006 push quota maps to HTTP 429/PT429')
        push_throttled = true
        break
      end
      raise "Expected a successful quota probe or HTTP 429, got HTTP #{code} (#{result['code']})" unless code == 200
    end
    elapsed = Process.clock_gettime(Process::CLOCK_MONOTONIC) - quota_started
    assert(push_throttled && elapsed < 60, 'migration 006 throttles the 31st push within one fixed window')

    code, result = call_api(DATA, 'rpc/saabtv_sync_v3_pull', body: {after_sequence: 0, max_results: 1}, token: b[:jwt])
    assert(code == 200, "migration 006 pull quota probe starts with a fresh measured request (HTTP #{code})")
    pull_throttled = false
    1.upto(12) do
      code, result = call_api(DATA, 'rpc/saabtv_sync_v3_pull', body: {after_sequence: 0, max_results: 1}, token: b[:jwt])
      if code == 429
        assert(result['code'] == 'PT429', 'migration 006 pull quota maps to HTTP 429/PT429')
        pull_throttled = true
        break
      end
      raise "Expected a successful pull quota probe or HTTP 429, got HTTP #{code} (#{result['code']})" unless code == 200
    end
    assert(pull_throttled, 'migration 006 throttles the 13th pull in the measured window')

    if ENV['SAAB_QA_VALIDATE_413'] == '1'
      padding = 'x' * (20 * 1024 * 1024)
      oversized_json = %({"operations":[{"padding":"#{padding}"}]})
      code, result = call_api(DATA, 'rpc/saabtv_sync_v3_push', raw_body: oversized_json, token: a[:jwt])
      assert(code == 413 && result['code'] == 'PT413', 'migration 006 rejects oversized v3 request as HTTP 413/PT413')
    end
  end
  rescue => error
    if ENV['SAAB_QA_INTERACTIVE'] == '1'
      puts "QA check failed: #{error.message}. Keeping only these two QA sessions in memory. Enter retry to retry checks, or cleanup to finish."
      retry if STDIN.gets&.strip == 'retry'
    end
    raise
  end
ensure
  users.each do |user|
    code, result = call_api(AUTH, 'delete-user', body: {password: user[:password]}, cookie: user[:cookie])
    puts "QA cleanup: #{code == 200 ? 'removed' : "requires cleanup (HTTP #{code}), username=#{user[:name]}"}"
  end
end
