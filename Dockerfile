FROM golang:1.25-alpine

# Build arguments for target platform (defaults: darwin/arm64 for Apple Silicon)
ARG GOOS=darwin
ARG GOARCH=arm64

RUN apk -U add git ca-certificates

WORKDIR /src

COPY . .

RUN go mod tidy && \
        go mod verify && \
        CGO_ENABLED=0 GOOS=${GOOS} GOARCH=${GOARCH} go build -o /txqr ./cmd/txqr
