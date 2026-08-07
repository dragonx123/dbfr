"""Web search + weather. Both use free, keyless APIs/services."""

from __future__ import annotations

import requests

from .base import tool


@tool(
    name="web_search",
    description=(
        "Search the web for current information (news, facts, prices, anything "
        "that might have changed since training). Returns a handful of result snippets."
    ),
    parameters={
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "The search query."},
            "max_results": {
                "type": "integer",
                "description": "Max number of results to return (default 5).",
            },
        },
        "required": ["query"],
    },
    requires="enable_web_search",
)
def web_search(query: str, max_results: int = 5):
    try:
        from duckduckgo_search import DDGS
    except ImportError:
        return "Error: duckduckgo-search is not installed. Run: pip install duckduckgo-search"

    results = []
    with DDGS() as ddgs:
        for r in ddgs.text(query, max_results=max_results):
            results.append(
                {
                    "title": r.get("title"),
                    "url": r.get("href"),
                    "snippet": r.get("body"),
                }
            )
    if not results:
        return "No results found."
    return results


@tool(
    name="get_weather",
    description="Get the current weather and short forecast for a place name (city, city+country, etc).",
    parameters={
        "type": "object",
        "properties": {
            "location": {
                "type": "string",
                "description": "Place name, e.g. 'Austin, TX' or 'Tokyo'.",
            },
        },
        "required": ["location"],
    },
    requires="enable_weather",
)
def get_weather(location: str):
    # 1. Geocode the place name (Open-Meteo's free geocoding API, no key required).
    geo = requests.get(
        "https://geocoding-api.open-meteo.com/v1/search",
        params={"name": location, "count": 1},
        timeout=10,
    ).json()
    results = geo.get("results")
    if not results:
        return f"Could not find a location matching '{location}'."
    place = results[0]
    lat, lon = place["latitude"], place["longitude"]
    label = ", ".join(
        filter(None, [place.get("name"), place.get("admin1"), place.get("country")])
    )

    # 2. Fetch current conditions (Open-Meteo forecast API, no key required).
    wx = requests.get(
        "https://api.open-meteo.com/v1/forecast",
        params={
            "latitude": lat,
            "longitude": lon,
            "current": "temperature_2m,apparent_temperature,relative_humidity_2m,"
            "precipitation,weather_code,wind_speed_10m",
            "temperature_unit": "fahrenheit",
            "wind_speed_unit": "mph",
            "precipitation_unit": "inch",
        },
        timeout=10,
    ).json()
    current = wx.get("current", {})
    if not current:
        return f"Weather lookup for {label} failed."

    return {
        "location": label,
        "temperature_f": current.get("temperature_2m"),
        "feels_like_f": current.get("apparent_temperature"),
        "humidity_pct": current.get("relative_humidity_2m"),
        "precipitation_in": current.get("precipitation"),
        "wind_mph": current.get("wind_speed_10m"),
        "condition": _WEATHER_CODES.get(current.get("weather_code"), "unknown"),
    }


_WEATHER_CODES = {
    0: "clear sky",
    1: "mostly clear",
    2: "partly cloudy",
    3: "overcast",
    45: "fog",
    48: "depositing rime fog",
    51: "light drizzle",
    53: "moderate drizzle",
    55: "dense drizzle",
    61: "slight rain",
    63: "moderate rain",
    65: "heavy rain",
    71: "slight snow",
    73: "moderate snow",
    75: "heavy snow",
    80: "rain showers",
    95: "thunderstorm",
    96: "thunderstorm with hail",
}
