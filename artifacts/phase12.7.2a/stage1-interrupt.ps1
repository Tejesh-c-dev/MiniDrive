Add-Type -AssemblyName System.Net.Http
$ErrorActionPreference='Stop'
$root='C:\MiniDrive\artifacts\phase12.7.2a'
$base='http://127.0.0.1:18087'
$project='md1272a-20261021-8f3c2a'
$client=[System.Net.Http.HttpClient]::new()
function Send-Api([string]$Method,[string]$Path,[byte[]]$Bytes,[string]$ContentType,[string]$Token) {
  $req=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method),$base+$Path)
  if($Token){$req.Headers.Authorization=[System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$Token)}
  if($null -ne $Bytes){$req.Content=[System.Net.Http.ByteArrayContent]::new($Bytes); $req.Content.Headers.ContentType=[System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($ContentType)}
  $at=[DateTime]::UtcNow.ToString('o')
  $res=$client.SendAsync($req).GetAwaiter().GetResult()
  $payload=$res.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
  return [pscustomobject]@{at=$at;status=[int]$res.StatusCode;bytes=$payload;text=[Text.Encoding]::UTF8.GetString($payload)}
}
$length=5*1024*1024+123
$fixture=Join-Path $root 'fixture.bin'
$pattern=[byte[]](0..255)
$stream=[IO.File]::Open($fixture,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None)
try { $remaining=$length; while($remaining -gt 0){$count=[Math]::Min($pattern.Length,$remaining);$stream.Write($pattern,0,$count);$remaining-=$count} } finally {$stream.Dispose()}
$hash=(Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash.ToLowerInvariant()
$email='phase1272a-20261021-8f3c2a@example.invalid'
$password=([guid]::NewGuid().ToString('N')+'A7!')
$registerJson=@{name='Phase 12.7.2A Disposable';email=$email;password=$password}|ConvertTo-Json -Compress
$register=Send-Api 'POST' '/api/auth/register' ([Text.Encoding]::UTF8.GetBytes($registerJson)) 'application/json' $null
if($register.status -ne 201){throw "register failed HTTP $($register.status)"}
$auth=$register.text|ConvertFrom-Json
$token=[string]$auth.token
$secure=ConvertTo-SecureString -String $token -AsPlainText -Force
ConvertFrom-SecureString -SecureString $secure | Set-Content -LiteralPath (Join-Path $root 'resume-token.dpapi') -Encoding ascii
$createJson=@{filename='md-phase12-7-2a-8f3c2a.bin';contentType='application/octet-stream';totalSizeBytes=$length}|ConvertTo-Json -Compress
$created=Send-Api 'POST' '/api/uploads' ([Text.Encoding]::UTF8.GetBytes($createJson)) 'application/json' $token
if($created.status -ne 201){throw "create session failed HTTP $($created.status)"}
$session=$created.text|ConvertFrom-Json
if($session.chunkSizeBytes -ne 5242880 -or $session.totalChunks -ne 2){throw 'unexpected server chunk plan'}
$before=Send-Api 'GET' "/api/uploads/$($session.id)" $null $null $token
if($before.status -ne 200){throw "pre-upload status failed HTTP $($before.status)"}
$beforeState=$before.text|ConvertFrom-Json
$all=[IO.File]::ReadAllBytes($fixture)
$first=[byte[]]::new(5242880)
[Array]::Copy($all,0,$first,0,$first.Length)
$chunk=Send-Api 'PUT' "/api/uploads/$($session.id)/chunks/0" $first 'application/octet-stream' $token
if($chunk.status -ne 200){throw "first chunk failed HTTP $($chunk.status)"}
$after=Send-Api 'GET' "/api/uploads/$($session.id)" $null $null $token
if($after.status -ne 200){throw "interruption status failed HTTP $($after.status)"}
$afterState=$after.text|ConvertFrom-Json
if($afterState.status -ne 'INITIATED' -or @($afterState.uploadedChunks).Count -ne 1 -or @($afterState.uploadedChunks)[0] -ne 0){throw 'interruption state assertion failed'}
$events=@(
 [pscustomobject]@{step='register';at=$register.at;method='POST';path='/api/auth/register';status=$register.status;response='token redacted; disposable account issued'},
 [pscustomobject]@{step='create_session';at=$created.at;method='POST';path='/api/uploads';status=$created.status;response=[pscustomobject]@{sessionId=$session.id;chunkSizeBytes=$session.chunkSizeBytes;totalChunks=$session.totalChunks;status=$session.status}},
 [pscustomobject]@{step='status_before_chunk';at=$before.at;method='GET';path="/api/uploads/$($session.id)";status=$before.status;response=$beforeState},
 [pscustomobject]@{step='upload_chunk_0';at=$chunk.at;method='PUT';path="/api/uploads/$($session.id)/chunks/0";status=$chunk.status;bytes=$first.Length;contentType='application/octet-stream';response=($chunk.text|ConvertFrom-Json)},
 [pscustomobject]@{step='status_at_interruption';at=$after.at;method='GET';path="/api/uploads/$($session.id)";status=$after.status;response=$afterState}
)
$state=[ordered]@{project=$project;backend='http://127.0.0.1:18087';accountId=$auth.userId;email=$email;sessionId=[string]$session.id;filename=[string]$session.filename;totalSizeBytes=$length;sourceSha256=$hash;chunkSizeBytes=$session.chunkSizeBytes;totalChunks=$session.totalChunks;interruptionAt=$after.at;events=$events}
$state|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $root 'stage1-evidence.json') -Encoding utf8
$client.Dispose()
[pscustomobject]@{stage='interrupt';sizeBytes=$length;sha256=$hash;accountId=$auth.userId;sessionId=$session.id;sessionStatus=$afterState.status;uploadedChunks=(@($afterState.uploadedChunks)-join ',');httpStatuses=($events|ForEach-Object{"$($_.step)=$($_.status)"}) -join ';'}|ConvertTo-Json -Compress


