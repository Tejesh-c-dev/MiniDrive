$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Net.Http
$root='C:\MiniDrive\artifacts\phase12.7.2a'
$base='http://127.0.0.1:18087'
$client=[System.Net.Http.HttpClient]::new()
function Send-Api([string]$Method,[string]$Path,[byte[]]$Bytes,[string]$ContentType,[string]$Token) {
  $req=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method),$base+$Path)
  if($Token){$req.Headers.Authorization=[System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$Token)}
  if($null -ne $Bytes){$req.Content=[System.Net.Http.ByteArrayContent]::new($Bytes);$req.Content.Headers.ContentType=[System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($ContentType)}
  $at=[DateTime]::UtcNow.ToString('o')
  $res=$client.SendAsync($req).GetAwaiter().GetResult()
  $payload=$res.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
  return [pscustomobject]@{at=$at;status=[int]$res.StatusCode;bytes=$payload;text=[Text.Encoding]::UTF8.GetString($payload)}
}
$state=Get-Content -LiteralPath (Join-Path $root 'stage1-evidence.json') -Raw|ConvertFrom-Json
$secure=(Get-Content -LiteralPath (Join-Path $root 'resume-token.dpapi') -Raw).Trim()|ConvertTo-SecureString
$token=[Net.NetworkCredential]::new('', $secure).Password
$sid=[string]$state.sessionId
$before=Send-Api 'GET' "/api/uploads/$sid" $null $null $token
if($before.status -ne 200){throw "resume status failed HTTP $($before.status)"}
$beforeState=$before.text|ConvertFrom-Json
if($beforeState.status -ne 'INITIATED' -or @($beforeState.uploadedChunks).Count -ne 1 -or @($beforeState.uploadedChunks)[0] -ne 0){throw 'pre-resume persisted state assertion failed'}
$all=[IO.File]::ReadAllBytes((Join-Path $root 'fixture.bin'))
$offset=[int]$state.chunkSizeBytes
$last=[byte[]]::new($all.Length-$offset)
[Array]::Copy($all,$offset,$last,0,$last.Length)
$chunk=Send-Api 'PUT' "/api/uploads/$sid/chunks/1" $last 'application/octet-stream' $token
if($chunk.status -ne 200){throw "remaining chunk failed HTTP $($chunk.status)"}
$complete=Send-Api 'POST' "/api/uploads/$sid/complete" $null $null $token
if($complete.status -ne 200){throw "complete failed HTTP $($complete.status)"}
$completed=$complete.text|ConvertFrom-Json
$fileId=[string]$completed.id
$status=Send-Api 'GET' "/api/uploads/$sid" $null $null $token
if($status.status -ne 200){throw "completed session status failed HTTP $($status.status)"}
$statusState=$status.text|ConvertFrom-Json
$metadataResponse=Send-Api 'GET' "/api/files/$fileId" $null $null $token
if($metadataResponse.status -ne 200){throw "metadata failed HTTP $($metadataResponse.status)"}
$metadata=$metadataResponse.text|ConvertFrom-Json
$listResponse=Send-Api 'GET' '/api/files' $null $null $token
if($listResponse.status -ne 200){throw "file list failed HTTP $($listResponse.status)"}
$list=@($listResponse.text|ConvertFrom-Json)
$named=@($list|Where-Object{$_.name -eq $state.filename})
$versionsResponse=Send-Api 'GET' "/api/files/$fileId/versions" $null $null $token
if($versionsResponse.status -ne 200){throw "versions failed HTTP $($versionsResponse.status)"}
$versions=@($versionsResponse.text|ConvertFrom-Json)
$download=Send-Api 'GET' "/api/files/$fileId/download" $null $null $token
if($download.status -ne 200){throw "download failed HTTP $($download.status)"}
$downloadPath=Join-Path $root 'downloaded.bin'
[IO.File]::WriteAllBytes($downloadPath,$download.bytes)
$downloadSize=(Get-Item -LiteralPath $downloadPath).Length
$downloadHash=(Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant()
$assertions=[ordered]@{
  sessionCompleted=($statusState.status -eq 'COMPLETED')
  fileMetadataSize=([long]$metadata.sizeBytes -eq [long]$state.totalSizeBytes)
  fileMetadataVersion=([int]$metadata.currentVersion -eq 1)
  singleLogicalFile=($named.Count -eq 1 -and [string]$named[0].id -eq $fileId)
  oneVersion=($versions.Count -eq 1 -and [int]$versions[0].versionNumber -eq 1 -and [long]$versions[0].sizeBytes -eq [long]$state.totalSizeBytes)
  downloadedSize=($downloadSize -eq [long]$state.totalSizeBytes)
  downloadedHash=($downloadHash -eq [string]$state.sourceSha256)
}
if(@($assertions.Values|Where-Object{$_ -ne $true}).Count -gt 0){throw "acceptance assertion failed: $($assertions|ConvertTo-Json -Compress)"}
$events=@(
 [pscustomobject]@{step='status_on_resume';at=$before.at;method='GET';path="/api/uploads/$sid";status=$before.status;response=$beforeState},
 [pscustomobject]@{step='upload_chunk_1';at=$chunk.at;method='PUT';path="/api/uploads/$sid/chunks/1";status=$chunk.status;bytes=$last.Length;contentType='application/octet-stream';response=($chunk.text|ConvertFrom-Json)},
 [pscustomobject]@{step='complete';at=$complete.at;method='POST';path="/api/uploads/$sid/complete";status=$complete.status;response=[pscustomobject]@{fileId=$fileId;name=$completed.name;sizeBytes=$completed.sizeBytes;currentVersion=$completed.currentVersion;checksum=$completed.checksum}},
 [pscustomobject]@{step='status_after_completion';at=$status.at;method='GET';path="/api/uploads/$sid";status=$status.status;response=$statusState},
 [pscustomobject]@{step='get_metadata';at=$metadataResponse.at;method='GET';path="/api/files/$fileId";status=$metadataResponse.status;response=[pscustomobject]@{id=$metadata.id;name=$metadata.name;sizeBytes=$metadata.sizeBytes;contentType=$metadata.contentType;checksum=$metadata.checksum;currentVersion=$metadata.currentVersion}},
 [pscustomobject]@{step='list_files';at=$listResponse.at;method='GET';path='/api/files';status=$listResponse.status;matchingFilenameCount=$named.Count},
 [pscustomobject]@{step='list_versions';at=$versionsResponse.at;method='GET';path="/api/files/$fileId/versions";status=$versionsResponse.status;response=$versions},
 [pscustomobject]@{step='download';at=$download.at;method='GET';path="/api/files/$fileId/download";status=$download.status;bytes=$downloadSize;sha256=$downloadHash}
)
$result=[ordered]@{fileId=$fileId;downloadPath=$downloadPath;downloadSizeBytes=$downloadSize;downloadSha256=$downloadHash;metadata=[pscustomobject]@{id=$metadata.id;name=$metadata.name;sizeBytes=$metadata.sizeBytes;contentType=$metadata.contentType;checksum=$metadata.checksum;currentVersion=$metadata.currentVersion};sessionAfter=$statusState;matchingFilenameCount=$named.Count;versions=$versions;assertions=$assertions;events=$events}
$result|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'stage2-evidence.json') -Encoding utf8
$client.Dispose()
[pscustomobject]@{stage='resume-complete';fileId=$fileId;downloadSizeBytes=$downloadSize;downloadSha256=$downloadHash;sessionStatus=$statusState.status;sessionUploadedChunks=(@($statusState.uploadedChunks)-join ',');matchingFilenameCount=$named.Count;versions=$versions.Count;assertions=($assertions|ConvertTo-Json -Compress);httpStatuses=($events|ForEach-Object{"$($_.step)=$($_.status)"}) -join ';'}|ConvertTo-Json -Compress

