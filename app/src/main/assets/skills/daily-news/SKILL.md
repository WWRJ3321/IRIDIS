---
name: daily-news
description: 汇总今日新闻（可指定主题），中文回答
---
1. Call web_search with the topic and "新闻" (news=true). Without a topic use "今日要闻".
2. If the results are thin, search once more with different words (for example in English).
3. Pick 5 items at most, newest first. Skip duplicates and ads.
4. Answer in Chinese: one line per item — title, one-sentence gist, source and date.
5. End with the links of the items, one per line.
Do not invent news. If the search fails, say so.
