FROM golang:1.25-alpine

RUN apk -U add git ca-certificates

WORKDIR /src

COPY . .

RUN go mod tidy && \
        go mod verify && \
        CGO_ENABLED=0 go build -o /txqr ./cmd/txqr
