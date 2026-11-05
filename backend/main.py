import json
import os
import time
import uuid
import base64
import hashlib
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import List, Optional

import requests
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

APP_DIR = Path(__file__).resolve().parent
IMAGE_DIR = APP_DIR / "generated"
IMAGE_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="Zaman Kaşifleri API", version="0.3.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/generated", StaticFiles(directory=str(IMAGE_DIR)), name="generated")


class StoryRequest(BaseModel):
    world: str = Field(min_length=2, max_length=60)
    mission: str = Field(min_length=2, max_length=400)
    minutes: int = Field(default=5, ge=3, le=10)
    age: int = Field(default=5, ge=4, le=6)


class Page(BaseModel):
    title: str
    narration: str
    dialogue: str = ""
    speaker: str = "Alp"
    image_prompt: str = ""
    image_url: Optional[str] = None


class Story(BaseModel):
    title: str
    introduction: str
    pages: List[Page]


CHARACTER_BIBLE = (
    "The only child hero is Alp, a 5-year-old boy with light brown slightly wavy/tousled hair "
    "and hazel-green eyes. He is curious, kind, brave in a thoughtful way, and playful. "
    "Show Alp as the same recognizable child on every page; keep face, hair, eye color, age "
    "and proportions consistent. His clothes may change to fit the era. Do not add other "
    "named child protagonists."
)

IMAGE_STYLE = (
    "High-quality children's picture-book illustration, polished cinematic 3D animated film "
    "look, warm inviting light, rich but natural colors, expressive friendly faces, clear "
    "storytelling composition, age-appropriate, no text, no letters, no watermark. "
    "The same Alp character design must be preserved from page to page."
)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "zaman-kasifleri",
        "provider": "openai",
        "images": "gpt-image-2",
        "image_storage": (
            "cloudinary"
            if all(os.getenv(key) for key in (
                "CLOUDINARY_CLOUD_NAME",
                "CLOUDINARY_API_KEY",
                "CLOUDINARY_API_SECRET",
            ))
            else "local_fallback"
        ),
        "cloudinary_missing_keys": [
            key for key in (
                "CLOUDINARY_CLOUD_NAME",
                "CLOUDINARY_API_KEY",
                "CLOUDINARY_API_SECRET",
            ) if not os.getenv(key)
        ],
    }


def openai_headers(api_key: str):
    return {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}


def generate_story_text(req: StoryRequest, api_key: str) -> Story:
    prompt = f"""Write a delightful Turkish illustrated adventure for one child, Alp, age {req.age}.
World/era: {req.world}. Mission: {req.mission}. Target reading duration: about {req.minutes} minutes.
Character guide: {CHARACTER_BIBLE}
Create exactly 6 pages. Return ONLY a valid JSON object with keys: title, introduction, pages.
Each page must contain:
- title: short Turkish page heading
- narration: 2-3 short, vivid, age-appropriate Turkish sentences
- dialogue: one short natural Turkish sentence spoken by Alp
- speaker: exactly "Alp"
- image_prompt: a detailed ENGLISH visual description of the exact scene, including Alp's action,
  setting, era-appropriate clothes, mood, and camera framing. Do not repeat generic style words.
Keep continuity between the six scenes. Give Alp a meaningful, safe, child-friendly problem to solve.
No frightening violence, injury, death, gore, weapons used to hurt people, or dangerous imitation
instructions. Resolve conflict through curiosity, kindness and cleverness. Avoid scary villains.
Finish warmly. No audio, narration directions, or sound effects."""
    payload = {
        "model": os.getenv("OPENAI_TEXT_MODEL", "gpt-6-luna"),
        "instructions": "You are an expert Turkish children's picture-book author. Output valid JSON only.",
        "input": prompt,
        "text": {"format": {"type": "json_object"}},
        "max_output_tokens": 5000,
    }
    try:
        response = requests.post(
            "https://api.openai.com/v1/responses",
            headers=openai_headers(api_key),
            json=payload,
            timeout=(20, 120),
        )
    except requests.RequestException as exc:
        raise HTTPException(502, f"OpenAI text connection failed: {exc}") from exc

    if response.status_code >= 400:
        detail = response.text[:1200]
        raise HTTPException(502, f"OpenAI text API error ({response.status_code}): {detail}")

    result = response.json()
    text = result.get("output_text")
    if not text:
        parts = []
        for item in result.get("output", []):
            for block in item.get("content", []):
                if block.get("type") in ("output_text", "text"):
                    parts.append(block.get("text", ""))
        text = "\n".join(parts)
    if not text:
        raise HTTPException(502, "OpenAI returned an empty story.")
    try:
        data = json.loads(text)
        story = Story.model_validate(data)
    except (ValueError, TypeError) as exc:
        raise HTTPException(502, f"OpenAI returned invalid story JSON: {exc}") from exc
    if len(story.pages) != 6:
        raise HTTPException(502, f"Expected 6 story pages; received {len(story.pages)}.")
    return story


def generate_one_image(page: Page, index: int, story_id: str, api_key: str) -> tuple[int, Optional[str]]:
    primary_prompt = (
        f"{IMAGE_STYLE}\n\n"
        f"Character continuity: {CHARACTER_BIBLE}\n\n"
        f"Scene: {page.image_prompt}\n"
        "Illustrate one single coherent moment from a premium children's picture book. "
        "Alp is clearly visible and is the central hero. Do not include written words."
    )

    # If the original scene is rejected by moderation, retry once with a simple,
    # peaceful alternative that does not repeat the rejected scene description.
    safe_fallback_prompt = (
        f"{IMAGE_STYLE}\n\n"
        f"Character continuity: {CHARACTER_BIBLE}\n\n"
        "Show Alp in a peaceful, cheerful storybook moment in a bright, welcoming setting. "
        "He is smiling with curiosity and kindness, surrounded by friendly colors and gentle "
        "storybook details. Keep the scene wholesome, calm, and suitable for a young child. "
        "Do not include danger, conflict, written words, or other central characters."
    )

    base_payload = {
        "model": os.getenv("OPENAI_IMAGE_MODEL", "gpt-image-2"),
        "size": os.getenv("OPENAI_IMAGE_SIZE", "1024x1024"),
        "quality": os.getenv("OPENAI_IMAGE_QUALITY", "medium"),
        "output_format": "png",
        "n": 1,
    }

    # Three attempts for temporary network/rate-limit/server errors.
    # A moderation rejection gets one separate, safer fallback attempt.
    transient_attempts = 3
    used_fallback = False
    attempt = 0

    while attempt < transient_attempts:
        attempt += 1
        payload = {
            **base_payload,
            "prompt": safe_fallback_prompt if used_fallback else primary_prompt,
        }
        try:
            response = requests.post(
                "https://api.openai.com/v1/images/generations",
                headers=openai_headers(api_key),
                json=payload,
                timeout=(20, 150),
            )

            if response.status_code == 400:
                try:
                    error = response.json().get("error", {})
                except ValueError:
                    error = {}
                error_code = error.get("code")
                error_type = error.get("type")
                if (
                    not used_fallback
                    and (
                        error_code == "moderation_blocked"
                        or error_type == "image_generation_user_error"
                    )
                ):
                    print(
                        f"Image page {index + 1} was rejected by moderation; "
                        "retrying once with a safer alternative prompt."
                    )
                    used_fallback = True
                    attempt = 0
                    continue

                print(
                    f"Image page {index + 1} failed: HTTP 400 "
                    f"{response.text[:500]}"
                )
                return index, None

            if response.status_code == 429 or response.status_code >= 500:
                if attempt < transient_attempts:
                    retry_after = response.headers.get("Retry-After")
                    try:
                        delay = min(max(float(retry_after), 1), 8) if retry_after else 2 ** attempt
                    except ValueError:
                        delay = 2 ** attempt
                    print(
                        f"Image page {index + 1}: temporary HTTP "
                        f"{response.status_code}; retry {attempt}/{transient_attempts - 1} "
                        f"in {delay}s."
                    )
                    time.sleep(delay)
                    continue

                print(
                    f"Image page {index + 1} failed after retries: "
                    f"HTTP {response.status_code} {response.text[:500]}"
                )
                return index, None

            if response.status_code >= 400:
                print(
                    f"Image page {index + 1} failed: HTTP {response.status_code} "
                    f"{response.text[:500]}"
                )
                return index, None

            encoded = response.json()["data"][0].get("b64_json")
            if not encoded:
                print(f"Image page {index + 1} failed: no b64_json in response")
                return index, None

            image_bytes = base64.b64decode(encoded)

            # On Render Free the local filesystem is ephemeral. If Cloudinary
            # credentials are configured, store the image there and return its
            # permanent HTTPS URL. Keep local storage as a development fallback.
            cloud_name = os.getenv("CLOUDINARY_CLOUD_NAME")
            cloud_api_key = os.getenv("CLOUDINARY_API_KEY")
            cloud_api_secret = os.getenv("CLOUDINARY_API_SECRET")

            if cloud_name and cloud_api_key and cloud_api_secret:
                timestamp = str(int(time.time()))
                public_id = f"zaman-kasifleri/{story_id}-{index + 1}"
                signature_params = f"public_id={public_id}&timestamp={timestamp}"
                signature = hashlib.sha1(
                    (signature_params + cloud_api_secret).encode("utf-8")
                ).hexdigest()
                try:
                    upload_response = requests.post(
                        f"https://api.cloudinary.com/v1_1/{cloud_name}/image/upload",
                        data={
                            "api_key": cloud_api_key,
                            "timestamp": timestamp,
                            "public_id": public_id,
                            "signature": signature,
                        },
                        files={"file": (f"{story_id}-{index + 1}.png", image_bytes, "image/png")},
                        timeout=(20, 90),
                    )
                    if upload_response.status_code >= 400:
                        print(
                            f"Cloudinary upload for page {index + 1} failed: "
                            f"HTTP {upload_response.status_code} {upload_response.text[:500]}"
                        )
                        return index, None
                    secure_url = upload_response.json().get("secure_url")
                    if not secure_url:
                        print(f"Cloudinary upload for page {index + 1} returned no secure_url")
                        return index, None
                    return index, secure_url
                except requests.RequestException as exc:
                    print(f"Cloudinary upload for page {index + 1} failed: {exc}")
                    return index, None

            filename = f"{story_id}-{index + 1}.png"
            (IMAGE_DIR / filename).write_bytes(image_bytes)
            return index, f"/generated/{filename}"

        except requests.RequestException as exc:
            if attempt < transient_attempts:
                delay = 2 ** attempt
                print(
                    f"Image page {index + 1}: connection error; "
                    f"retry {attempt}/{transient_attempts - 1} in {delay}s: {exc}"
                )
                time.sleep(delay)
                continue
            print(f"Image page {index + 1} failed after retries: {exc}")
            return index, None
        except (KeyError, IndexError, ValueError) as exc:
            print(f"Image page {index + 1} failed while reading response: {exc}")
            return index, None

    return index, None


@app.post("/story")
def create_story(req: StoryRequest):
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise HTTPException(503, "OPENAI_API_KEY is not configured in Render.")

    story = generate_story_text(req, api_key)
    story_id = uuid.uuid4().hex[:12]

    # Generate images concurrently in small batches to keep a six-page story practical
    # on Render Free. If an image fails, the text story is still returned.
    max_workers = min(3, len(story.pages))
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = [
            pool.submit(generate_one_image, page, i, story_id, api_key)
            for i, page in enumerate(story.pages)
        ]
        for future in as_completed(futures):
            index, image_path = future.result()
            if image_path:
                # Cloudinary already returns an absolute HTTPS URL.
                # Only prefix the Render base URL for local /generated paths.
                if image_path.startswith(("https://", "http://")):
                    story.pages[index].image_url = image_path
                else:
                    base_url = os.getenv(
                        "PUBLIC_BASE_URL",
                        "https://zaman-kasifleri.onrender.com",
                    ).rstrip("/")
                    story.pages[index].image_url = f"{base_url}{image_path}"
            else:
                story.pages[index].image_url = None

    return {
        "title": story.title,
        "introduction": story.introduction,
        "pages": [page.model_dump() for page in story.pages],
    }
