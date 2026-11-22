rootProject.name = "ad-commerce-lab"

include(
    "services:common",
    "services:catalog",
    "services:campaign",
    "services:payment",
    "services:mock-pg",
    "services:gateway",
    "services:delivery",
    "services:settlement",
    "services:experience",
)
