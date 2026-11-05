# Render Replay

Records what the client sends to the GPU renderer and plays it back in the game viewport. It is not a screen video:
playback issues the recorded draw calls again, so the client re-renders the recorded scene, plugin overlays included.

- **Record / Playback** from the side panel, into four slots. Needs the GPU plugin on.
- **Timeline** to seek, pause and step one game tick. During playback Left and Right step a tick and Space pauses.
- **Export / Import** a recording through the clipboard.

**During playback the game cannot be interacted with.** You see the recording, not the live game, and your clicks do
nothing until playback ends.

The plugin only reads client state and wraps the renderer; it sends nothing anywhere. A recording includes the
interface and chat box as they looked, so treat an exported one like a screenshot. Files are kept in
`.runelite/render-replay/`.
