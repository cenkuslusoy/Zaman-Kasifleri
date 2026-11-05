import base64
import json
import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Optional

import requests
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel, Field

app = FastAPI(title="Zaman Kaşifleri API", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

OPENAI_API = "https://api.openai.com/v1"
TEXT_MODEL = os.getenv("OPENAI_TEXT_MODEL", "gpt-6-luna")
IMAGE_MODEL = os.getenv("OPENAI_IMAGE_MODEL", "gpt-image-2")
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "https://zaman-kasifleri.onrender.com").rstrip("/")
IMAGE_CACHE = {}
IMAGE_LOCK = threading.Lock()

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

CHARACTER_BIBLE = """
Create the same three child heroes in every illustration, in a polished, warm,
high-quality 3D animated picture-book style. Güney: short dark brown hair,
blue clothing, energetic expression. Aras: soft curly light-brown hair,
freckles, red scarf, cheerful expression. Alp: light-brown slightly wavy
tousled hair, hazel/green-brown eyes, curious gentle expression. Alp is the
main viewpoint character. They are friendly young children, never teenagers.
Keep face, hair, age, body proportions and art style consistent. Their outfits
may adapt naturally to the selected historical/fantasy world. No text, letters,
watermarks, borders or speech bubbles in the image. Wide cinematic landscape
composition, clear readable silhouettes, child-safe and inviting.
"""

def openai_headers(key):
    return {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}

@app.get("/health")
def health():
    return {"status": "ok", "service": "zaman-kasifleri", "provider": "openai"}

def generate_story_text(key: str, req: StoryRequest) -> Story:
    prompt = f"""Write a delightful Turkish illustrated adventure for children aged {req.age}.
World/era: {req.world}. Mission: {req.mission}. Approximate reading time: {req.minutes} minutes.
The heroes are best friends Güney, Aras and Alp. Alp is the central viewpoint character.
Return JSON only with keys title, introduction, pages. Exactly 6 pages.
Each page: title, narration (2-3 short, vivid child-friendly Turkish sentences),
dialogue (one short Turkish line), speaker (Güney, Aras or Alp), image_prompt
(an English visual description of that exact scene; include the three heroes and
their distinguishing visual traits). Maintain continuity between consecutive scenes.
No frightening violence, injury, death, gore, weapons used to hurt anyone, or dangerous
imitation instructions. Resolve challenges through kindness, teamwork and cleverness.
For historical settings, avoid presenting conquest or cruelty as heroic. End warmly.
"""
    payload = {
        "model": TEXT_MODEL,
        "messages": [
            {"role": "system", "content": "You are an expert Turkish picture-book author. Follow child-safety rules. Return valid JSON only."},
            {"role": "user", "content": prompt}
        ],
        "response_format": {"type": "json_object"},
        "temperature": 0.8,
        "max_tokens": 4500
    }
    try:
        r = requests.post(f"{OPENAI_API}/chat/completions", headers=openai_headers(key),
                          json=payload, timeout=90)
        if r.status_code >= 400:
            raise HTTPException(502, f"OpenAI story error: {r.text[:900]}")
        raw = r.json()["choices"][0]["message"]["content"]
        data = json.loads(raw)
        story = Story.model_validate(data)
        if len(story.pages) != 6:
            raise ValueError("Model did not return exactly six pages")
        return story
    except HTTPException:
        raise
    except (requests.RequestException, KeyError, IndexError, ValueError) as exc:
        raise HTTPException(502, f"Story generation failed: {str(exc)[:500]}")

def generate_one_image(key: str, page: Page, index: int):
    prompt = f"""{CHARACTER_BIBLE}
Scene {index + 1} of a continuous six-page children's story.
World: {page.image_prompt}
Illustrate this exact moment: {page.narration}
Use the same three recognizable child heroes and coherent visual design.
Do not render any written words.
"""
    payload = {
        "model": IMAGE_MODEL,
        "prompt": prompt,
        "size": "1024x1024",
        "quality": os.getenv("OPENAI_IMAGE_QUALITY", "low"),
        "output_format": "jpeg"
    }
    r = requests.post(f"{OPENAI_API}/images/generations", headers=openai_headers(key),
                      json=payload, timeout=180)
    if r.status_code >= 400:
        raise RuntimeError(f"Image {index + 1}: HTTP {r.status_code} {r.text[:500]}")
    encoded = r.json()["data"][0].get("b64_json")
    if not encoded:
        raise RuntimeError(f"Image {index + 1}: no image data returned")
    image_bytes = base64.b64decode(encoded)
    image_id = uuid.uuid4().hex
    with IMAGE_LOCK:
        IMAGE_CACHE[image_id] = image_bytes
    return index, f"{PUBLIC_BASE_URL}/images/{image_id}"

@app.get("/images/{image_id}")
def get_image(image_id: str):
    with IMAGE_LOCK:
        content = IMAGE_CACHE.get(image_id)
    if content is None:
        raise HTTPException(404, "Image expired; create the story again")
    return Response(content=content, media_type="image/jpeg",
                    headers={"Cache-Control": "public, max-age=86400"})

@app.post("/story")
def create_story(req: StoryRequest):
    key = os.getenv("OPENAI_API_KEY")
    if not key:
        raise HTTPException(503, "OPENAI_API_KEY is not configured in Render")
    story = generate_story_text(key, req)

    # Generate three at a time to keep memory and provider concurrency reasonable.
    errors = []
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs = {pool.submit(generate_one_image, key, page, i): i
                for i, page in enumerate(story.pages)}
        for job in as_completed(jobs):
            try:
                index, url = job.result()
                story.pages[index].image_url = url
            except Exception as exc:
                errors.append(str(exc))

    if errors:
        # Do not return a half-illustrated book: surface a clear provider error.
        raise HTTPException(502, "Image generation failed: " + " | ".join(errors)[:900])

    return {
        "title": story.title,
        "introduction": story.introduction,
        "pages": [page.model_dump() for page in story.pages]
    }
