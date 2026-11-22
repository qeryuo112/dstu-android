package com.deepstudent.app.content

import com.deepstudent.features.content.api.ContentDocument

internal enum class ContentMode(val label: String, val document: ContentDocument) {
    Formula("公式", ContentDocument.Formula("""\int_0^1 x^2 \, dx = \frac{1}{3}""")),
    Diagram("图表", ContentDocument.Diagram("flowchart LR\n    A[笔记] --> B[整理]\n    B --> C[复习]")),
    Card("HTML 卡面", ContentDocument.HtmlCard(CARD_HTML)),
}

private const val CARD_HTML = """
<style>
  .card { max-width: 560px; margin: 24px auto; font: 16px/1.6 sans-serif; }
  .card h2 { font-size: 1.25rem; margin: 0 0 16px; }
  .card p { margin: 0 0 16px; }
  .card button { font: inherit; color: var(--foreground); background: var(--background);
    border: 1px solid var(--primary); border-radius: 4px; padding: 8px 14px; margin: 0 8px 8px 0; }
  .card button:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
  .card .answer { border-top: 1px solid var(--primary); padding-top: 16px; }
</style>
<main class="card">
  <h2>间隔重复</h2>
  <p>什么是间隔重复？</p>
  <div id="front"><button id="reveal" type="button">显示答案</button></div>
  <div id="back" class="answer" hidden>
    <p>在逐渐延长的间隔后复习，帮助巩固长期记忆。</p>
    <button type="button" data-dstu-answer="再复习">再复习</button>
    <button type="button" data-dstu-answer="已掌握">已掌握</button>
    <button type="button" data-dstu-replay>重新开始</button>
  </div>
</main>
<script>
  const front = document.getElementById('front');
  const back = document.getElementById('back');
  document.getElementById('reveal').addEventListener('click', () => {
    front.hidden = true;
    back.hidden = false;
  });
  document.querySelector('[data-dstu-replay]').addEventListener('click', () => {
    front.hidden = false;
    back.hidden = true;
  });
</script>
"""
