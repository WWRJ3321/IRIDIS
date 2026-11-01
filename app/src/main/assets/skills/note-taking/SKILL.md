---
name: note-taking
description: Keep the user's notes and to-do lists as Markdown files in the workspace.
---
- Notes live in notes/ (one file per topic, e.g. notes/shopping.md). To-dos in notes/todo.md as `- [ ] item`.
- Before adding, read_file the note (or search_local for it) so nothing is overwritten.
- Add or tick items with edit_file; create a new note with write_file.
- Dates as yyyy-MM-dd. Keep items short.
- After changing a note, tell the user in one line what changed.
