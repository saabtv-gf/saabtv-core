# Smoke-tests the deployed API with isolated, generated QA users.
# Never outputs passwords, cookies, JWTs or real user data.
require 'net/http'
require 'json'
require 'securerandom'
$stdout.sync = true

AUTH = 'https://ep-gentle-voice-azd9if2p.neonauth.c-3.ap-southeast-1.aws.neon.tech/neondb/auth'
DATA = 'https://ep-gentle-voice-azd9if2p.apirest.c-3.ap-southeast-1.aws.neon.tech/neondb/rest/v1'
def call_api(base, path, body: nil, cookie: nil, token: nil)
  uri = URI("#{base}/#{path}")
  req = body ? Net::HTTP::Post.new(uri) : Net::HTTP::Get.new(uri)
  req['Content-Type'] = 'application/json'
  req['Origin'] = URI(AUTH).then { |value| "#{value.scheme}://#{value.host}" } if base == AUTH
  req['Cookie'] = cookie if cookie
  req['Authorization'] = "Bearer #{token}" if token
  req.body = JSON.generate(body) if body
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 15, read_timeout: 30) { |http| http.request(req) }
  json = JSON.parse(res.body) rescue {}
  [res.code.to_i, json, res.get_fields('set-cookie') || []]
end
def assert(condition, message)
  raise message unless condition
  puts "PASS #{message}"
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
