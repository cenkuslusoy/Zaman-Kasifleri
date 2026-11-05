import json
import os
from typing import List, Optional

import requests
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

app = FastAPI(title="Zaman Kaşifleri API", version="0.2.1")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

class StoryRequest(BaseModel):
    world: str = Field(min_length=2, max_length=60)
    mission: str = Field(min_length=2, max_length=400)
    minutes: int = Field(default=5, ge=3, le=10)
    age: int = Field(default=5, ge=4, le=6)

class Page(BaseModel):
    title: str
    narration: str
    dialogue: str = ""
    speaker: str = "Anlatıcı"
    image_prompt: str = ""
    image_url: Optional[str] = None

class Story(BaseModel):
    title: str
    introduction: str
    pages: List[Page]

CHARACTER_BIBLE = (
    "Güney: energetic and playful boy; Aras: clever and funny boy; "
    "Alp: curious, cheeky and kind leader. Three close child friends. "
    "Keep their identities and appearance consistent across the story."
)

@app.get("/health")
def health():
    return {"status": "ok", "service": "zaman-kasifleri"}

@app.post("/story")
def create_story(req: StoryRequest):
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise HTTPException(503, "GEMINI_API_KEY is not configured")

    prompt = f"""Write a Turkish illustrated adventure story for children aged {req.age}.
World/era: {req.world}. Mission: {req.mission}. Target spoken duration: {req.minutes} minutes.
Main trio: Güney, Aras and Alp, best friends. {CHARACTER_BIBLE}
Return ONLY valid JSON with keys title, introduction, pages. Make 6 pages.
Each page must have title, narration (2-3 short child-friendly sentences),
dialogue (one short line), speaker (one of Güney, Aras, Alp), image_prompt (English visual prompt).
No frightening violence, weapons used to hurt people, death, gore, or dangerous imitation instructions.
Conflict is solved through teamwork, kindness and cleverness. For historical settings, distinguish
fact from fantasy gently; do not present harmful colonial conquest as heroic. End warmly.
Images are disabled in this free MVP, so image_prompt is descriptive text only."""

    model = os.getenv("GEMINI_MODEL", "gemini-3.8-flash")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    payload = {
        "system_instruction": {
            "parts": [{"text": "You create safe, age-appropriate Turkish picture-book stories. Output valid JSON only."}]
        },
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {
            "temperature": 0.8,
            "responseMimeType": "application/json",
            "maxOutputTokens": 5000
        }
    }
    try:
        response = requests.post(url, params={"key": api_key}, json=payload, timeout=90)
        if response.status_code >= 400:
            raise HTTPException(response.status_code, f"Gemini API error: {response.text[:1200]}")
        result = response.json()
        text = result["candidates"][0]["content"]["parts"][0]["text"]
        data = json.loads(text)
        story = Story.model_validate(data)
        for page in story.pages:
            page.image_url = None
        return {
            "title": story.title,
            "introduction": story.introduction,
            "pages": [p.model_dump() for p in story.pages]
        }
    except HTTPException:
        raise
    except (KeyError, IndexError, ValueError, requests.RequestException) as exc:
        raise HTTPException(502, f"Story generation failed: {exc}")
