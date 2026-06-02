import subprocess, zipfile, os, tempfile, ctypes

model_jar = r'C:\Users\josen\.gradle\caches\modules-2\files-2.1\com.agentclientprotocol\acp-model-jvm\0.24.0\a80e1b370aedaf9254d374031ff4aef32dd5371d\acp-model-jvm-0.24.0.jar'
acp_jar = r'C:\Users\josen\.gradle\caches\modules-2\files-2.1\com.agentclientprotocol\acp-jvm\0.24.0\bf5356ad7bea9afc511324725cc884a2b9a47505\acp-jvm-0.24.0.jar'
javap = r'C:\Program Files\JetBrains\IntelliJ IDEA 2025.1\jbr\bin\javap.exe'

buf = ctypes.create_unicode_buffer(260)
n = ctypes.windll.kernel32.GetShortPathNameW(javap, buf, 260)
javap_short = buf.value[:n]

tmpdir = tempfile.mkdtemp()

# Model classes to inspect
model_classes = [
    'com/agentclientprotocol/model/StopReason.class',
    'com/agentclientprotocol/model/SessionUpdate.class',
    'com/agentclientprotocol/model/SessionUpdate$ToolCallUpdate.class',
    'com/agentclientprotocol/model/SessionUpdate$UserMessageChunk.class',
    'com/agentclientprotocol/model/SessionUpdate$AgentMessageChunk.class',
    'com/agentclientprotocol/model/SessionUpdate$PlanUpdate.class',
    'com/agentclientprotocol/model/PromptResponse.class',
    'com/agentclientprotocol/model/AgentCapabilities.class',
    'com/agentclientprotocol/model/PromptCapabilities.class',
    'com/agentclientprotocol/model/McpCapabilities.class',
    'com/agentclientprotocol/model/SessionCapabilities.class',
    'com/agentclientprotocol/model/SessionId.class',
    'com/agentclientprotocol/model/PlanEntry.class',
    'com/agentclientprotocol/model/PlanEntryStatus.class',
    'com/agentclientprotocol/model/PermissionOption.class',
    'com/agentclientprotocol/model/PermissionOptionId.class',
    'com/agentclientprotocol/model/PermissionOptionKind.class',
    'com/agentclientprotocol/model/AvailableCommand.class',
    'com/agentclientprotocol/model/ToolCallContent.class',
    'com/agentclientprotocol/model/ToolCallContent$Content.class',
    'com/agentclientprotocol/model/ContentBlock.class',
    'com/agentclientprotocol/model/ContentBlock$Text.class',
    'com/agentclientprotocol/model/ContentBlock$Image.class',
    'com/agentclientprotocol/model/ToolCallId.class',
    'com/agentclientprotocol/model/ToolCallStatus.class',
    'com/agentclientprotocol/model/ToolKind.class',
    'com/agentclientprotocol/model/PlanEntryPriority.class',
]

# ACP JAR classes
acp_classes = [
    'com/agentclientprotocol/transport/StdioTransport.class',
    'com/agentclientprotocol/transport/Transport.class',
    'com/agentclientprotocol/transport/BaseTransport.class',
    'com/agentclientprotocol/agent/Agent.class',
    'com/agentclientprotocol/agent/AgentInfo.class',
    'com/agentclientprotocol/common/Event.class',
    'com/agentclientprotocol/common/SessionCreationParameters.class',
    'com/agentclientprotocol/rpc/JsonRpcMessage.class',
    'com/agentclientprotocol/agent/AgentSupport.class',
    'com/agentclientprotocol/agent/AgentSession.class',
]

with zipfile.ZipFile(model_jar, 'r') as jar:
    for cls in model_classes:
        try:
            jar.extract(cls, tmpdir)
            extracted = os.path.join(tmpdir, cls)
            result = subprocess.run([javap_short, '-p', extracted], capture_output=True, text=True)
            print(f'=== {cls} ===')
            if result.stdout:
                print(result.stdout)
            else:
                print(result.stderr)
            print()
        except Exception as e:
            print(f'=== {cls} === ERROR: {e}\n')

with zipfile.ZipFile(acp_jar, 'r') as jar:
    for cls in acp_classes:
        try:
            jar.extract(cls, tmpdir)
            extracted = os.path.join(tmpdir, cls)
            result = subprocess.run([javap_short, '-p', extracted], capture_output=True, text=True)
            print(f'=== {cls} ===')
            if result.stdout:
                print(result.stdout)
            else:
                print(result.stderr)
            print()
        except Exception as e:
            print(f'=== {cls} === ERROR: {e}\n')
