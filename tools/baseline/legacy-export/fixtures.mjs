export const FIXTURES = {
  appVersion: '0.20.0',
  folder: { id: 'fld_legacy_demo', title: '旧设备示例' },
  notes: [
    {
      title: '迁移示例笔记',
      content: '# 迁移示例\n\n这是公开 fixture 笔记，用于验证旧包导入。\n\n参见 [[迁移示例笔记二]]。\n',
      tags: ['fixture', '迁移'],
    },
    {
      title: '迁移示例笔记二',
      content: '## 代码块\n\n```ts\nconst answer = 42;\n```\n\n- 列表项一\n- 列表项二\n',
      tags: ['fixture'],
    },
  ],
  file: {
    name: 'legacy-sample.txt',
    text: 'Deep Student legacy export fixture.\n公开样本，无用户数据。\n',
    mimeType: 'text/plain',
    extension: 'txt',
    originalPath: '/sdcard/Download/legacy-sample.txt',
  },
  chat: {
    sessionId: 'sess_legacy_demo',
    sessionTitle: '旧设备会话',
    userId: 'msg_legacy_user',
    assistantId: 'msg_legacy_assistant',
    blockId: 'blk_legacy_answer',
    answer: '这是旧设备上的公开示例回答。',
  },
  mistakes: {
    id: 'mistake_legacy_demo',
    question: '示例题目：1 + 1 = ?',
    ocrText: '示例识别文本',
    ocrNote: '公开 fixture，无真实用户内容',
    type: 'math',
    message: '这是公开 fixture 的错题消息。',
  },
  anki: {
    taskId: 'task_legacy_demo',
    documentId: 'doc_legacy_demo',
    cardId: 'card_legacy_demo',
    front: '示例问题',
    back: '示例答案',
  },
  settings: { themeKey: 'appearance.theme', themeValue: 'system', folderKey: 'notes.default_folder' },
  usage: { callerType: 'chat_v2', modelId: 'deepseek-chat', promptTokens: 120, completionTokens: 80, provider: 'deepseek', date: '2026-10-01' },
  audit: { id: 'audit_legacy_demo', operationType: 'Migration', target: 'vfs' },
  timestamps: {
    iso: '2026-10-01T00:00:00.000Z',
    isoMs: '2026-10-01T00:00:01.000Z',
    folderMs: 1790000000000,
  },
};
