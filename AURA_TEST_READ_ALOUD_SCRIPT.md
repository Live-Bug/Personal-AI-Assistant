# 🎙️ AURA Spoken Test Script (Read-Aloud Testing Guide)

Follow this script step-by-step to test all features of AURA.
Hold your phone in front of you, open the AURA app, and ensure the top badge shows **`[📁 Gemma 2B]`** and the status says **`Standby (tap to listen)`**.

---

## 🟢 SETUP: Start Listening
👉 **Action:** Tap the large microphone button in the center bottom of your screen.  
👀 **Check:** The button turns **Green**, and the status banner at the top changes to **`Listening...`**.

---

## 🧪 STEP 1: Small Talk Test (Testing Noise Filtering)

*Speak naturally at normal volume:*

> **"Hey, how's it going? Yeah, the traffic today was completely crazy, took me like an hour just to get across town. Haha, totally wild."**

⏳ **`[Pause 5 seconds — stay silent]`**

👀 **What should happen on screen:**
1. While you were talking, a gray ghost card with a green ear icon appeared at the bottom showing your words in real time.
2. After 2–3 seconds, your sentence solidifies into a permanent transcript card on the Home screen.
3. Because this is casual chit-chat, Gemma's memory filter will classify it as small talk and **will NOT** pollute your memory database with it.

---

## 🧪 STEP 2: Action Item & Deadline Extraction Test

*Speak clearly:*

> **"Oh, by the way, remember to send the final project report to Sarah by 4 PM tomorrow. Make sure to double check all the numbers."**

⏳ **`[Pause 6 seconds — stay silent]`**

👀 **What should happen on screen:**
1. The transcript card updates with your spoken sentence.
2. Gemma 2B processes the audio on-device.
3. 👉 **Action:** Tap the **`Tasks`** tab at the bottom of your screen.
   - You should see a brand-new task:
   - 📋 **Title:** `"Send the final project report to Sarah"`
   - ⏰ **Deadline Badge:** `"4 PM tomorrow"`
4. 👉 Tap the checkbox next to it to mark it completed, then uncheck it.
5. 👉 Return to the **`Aura`** (Home) tab.

---

## 🧪 STEP 3: Multiple Tasks in One Breath Test

*Speak naturally:*

> **"Also, don't forget to push the git repository to the team before midnight, and remember to buy groceries tonight at 8 PM."**

⏳ **`[Pause 6 seconds — stay silent]`**

👀 **What should happen on screen:**
1. Tap the **`Tasks`** tab again.
2. You should now see two additional tasks automatically created:
   - 📋 `"Push the git repository to the team"` (Deadline: `"midnight"`)
   - 📋 `"Buy groceries"` (Deadline: `"tonight at 8 PM"`)
3. 👉 Return to the **`Aura`** (Home) tab.

---

## 🧪 STEP 4: The 30-Second Silence Watchdog Test

*Speak this short sentence:*

> **"That's everything on the agenda for our meeting today."**

⏳ **`[DO NOT SPEAK FOR 30 SECONDS STRAIGHT — COMPLETE SILENCE]`**  
*(Watch your phone screen during these 30 seconds)*

👀 **What should happen:**
1. Notice that the app **does NOT** beep aggressively every 5 seconds anymore! It rests quietly and stays in listening mode.
2. Once the 30-second silence watchdog expires, the buffer flushes.
3. Gemma 2B runs an ambient summarization on today's conversation block.
4. A purple system card appears in the feed summarizing what you discussed!

---

## 🧪 STEP 5: Real-Time Weather & Dynamic City Test

👉 **Action:** Tap the purple **`Ask Aura`** button (the chat bubble icon on the bottom left).  
👉 A popup appears. Tap the microphone icon inside the text field or type:

> **"What is the weather in Delhi?"**

👉 Tap **`Ask`** (or let voice finish).

👀 **What should happen:**
1. The top badge briefly turns blue: **`[📡 Online]`** (fetching live OpenWeather data).
2. Aura speaks aloud using TTS:
   - 🗣️ *"The weather in Delhi is ...°C, feels like ...°C, [description]."*
3. An AI Response card appears in the live feed with the exact weather data.
4. 👉 **Action:** Tap the **`Privacy`** tab at the bottom.
   - Under **Network Calls**, you will see a live log entry:
   - `WEATHER | Query: Delhi | Status: 200 OK`
   - Proving that Delhi was fetched, but **zero audio was uploaded**.

---

## 🧪 STEP 6: Memory Recall & Second Brain Test

👉 **Action:** Tap the purple **`Ask Aura`** button again.  
👉 Speak or type:

> **"What tasks do I have scheduled for tomorrow?"**

👉 Tap **`Ask`**.

👀 **What should happen:**
1. Because this is a memory question, **zero network calls are made** (badge stays `Local`).
2. Gemma 2B queries SQLite Room on-device.
3. Aura speaks aloud and prints:
   - 🗣️ *"You have a task to send the final project report to Sarah by 4 PM tomorrow."*
4. It successfully recalled your earlier conversation without any cloud servers!
