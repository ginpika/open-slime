/** Decode UTF-8 characters for display without changing URL delimiters or double decoding. */
export function readableUrl(url: string): string {
  return url.replace(/%(?:[cC][2-9a-fA-F](?:%[89aAbB][0-9a-fA-F])|[eE][0-9a-fA-F](?:%[89aAbB][0-9a-fA-F]){2}|[fF][0-4](?:%[89aAbB][0-9a-fA-F]){3})/g, encoded => {
    try {
      const decoded = decodeURIComponent(encoded)
      return /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(decoded) ? encoded : decoded
    } catch { return encoded }
  })
}

export function navigationUrl(input: string): string {
  return new URL(input.trim()).href
}
