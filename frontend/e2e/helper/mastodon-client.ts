import * as path from 'path';
import * as fs from 'fs';

// WARNING: Only do this in a development environment with local mastodon.
// Never disable certificate checking in productive environments.
process.env['NODE_TLS_REJECT_UNAUTHORIZED'] = '0';

const url = process.env['MASTODON_USER_API_URL'] || 'https://proxy';
const accessToken = process.env['MASTODON_USER_ACCESS_TOKEN'] || 'pyPuRhw4cZJHN4QJuMX8mo9CFmziZp_BjvuCf71sV34';

export async function createTextToot(
  text: string = 'Hi Glacier.\nThis is a test toot.',
  visibility: string = 'public'
): Promise<string> {
  try {
    const endpoint = `${url}/api/v1/statuses`;

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        status: text,
        visibility: visibility
      }),
    });

    if (!response.ok) {
      console.error(`Couldn't create status: ${response.status} ${response.statusText}`);
    }
    const data = await response.json();
    // console.log('Toot created successfully:', data);
    return data.id;
  } catch (error) {
    console.error('Error during creation of status:', error);
    return "";
  }
}

export async function modifyTextToot(
  id: string,
  text: string = 'Hi Glacier.\nThis is a test toot.',
  visibility: string = 'public'
): Promise<string> {
  try {
    const endpoint = `${url}/api/v1/statuses/${id}`;

    const response = await fetch(endpoint, {
      method: 'PUT',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        status: text,
        visibility: visibility
      }),
    });

    if (!response.ok) {
      console.error(`Couldn't modify status: ${response.status} ${response.statusText}`);
    }
    const data = await response.json();
    // console.log('Toot created successfully:', data);
    return data.id;
  } catch (error) {
    console.error('Error during modification of status:', error);
    return "";
  }
}


export async function deleteToot(
  id: string,
): Promise<string> {
  try {
    const endpoint = `${url}/api/v1/statuses/${id}`;

    const response = await fetch(endpoint, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
      }
    });

    if (!response.ok) {
      console.error(`Couldn't delete status: ${response.status} ${response.statusText}`);
    }
    const data = await response.json();
    // console.log('Toot created successfully:', data);
    return data.id;
  } catch (error) {
    console.error('Error during deletion of status:', error);
    return "";
  }
}

export async function createMediaToot(
  text: string = 'Hi Glacier.\nThis is a test toot.',
  mediaPath: string = 'mastodon.jpeg',
  description: string = 'A cute mastodon in front of a glacier',
  visibility: string = 'public'
): Promise<void> {

  const filePath = path.resolve(__dirname, mediaPath);
  console.log('Media file path:', filePath);
  if (!fs.existsSync(filePath)) {
    console.error('Media file does not exists:', filePath);
  }

  try {
    const mediaId = await uploadMedia(filePath, description);

    const endpoint = `${url}/api/v1/statuses`;

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        status: text,
        media_ids: [mediaId],
        visibility: visibility
      })
    });

    if (!response.ok) {
      console.error(`Couldn't create status: ${response.status} ${response.statusText}`);
    }

    const data = await response.json();
    // console.log('Toot created successfully:', data);
  } catch (error) {
    console.error('Error during creation of status:', error);
  }
}

/**
 * Object-oriented wrapper around the Mastodon status API.
 *
 * Some specs (share-link.spec.ts, share-link-fallback.spec.ts,
 * share-link-killswitch.spec.ts) construct an explicit client and call
 * {@link postToot}, rather than the module-level helper functions. The
 * constructor honors the (apiUrl, token) passed in; both default to the same
 * env-driven values the standalone helpers use, so behavior is identical when
 * the spec forwards `process.env['MASTODON_USER_API_URL']` /
 * `process.env['MASTODON_USER_ACCESS_TOKEN']`.
 */
export class MastodonClient {
  constructor(
    private readonly apiUrl: string = url,
    private readonly token: string = accessToken,
  ) {}

  /**
   * Create a public status (toot) against this client's configured instance.
   * Mirrors {@link createTextToot}. Returns the new status id, or "" on error.
   */
  async postToot(text: string, visibility: string = 'public'): Promise<string> {
    try {
      const response = await fetch(`${this.apiUrl}/api/v1/statuses`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${this.token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          status: text,
          visibility: visibility,
        }),
      });

      if (!response.ok) {
        console.error(`Couldn't create status: ${response.status} ${response.statusText}`);
      }
      const data = await response.json();
      return data.id;
    } catch (error) {
      console.error('Error during creation of status:', error);
      return "";
    }
  }
}

async function uploadMedia(filePath: string, description: string): Promise<string> {
  try {
    const endpoint = `${url}/api/v2/media`;

    const file = fs.readFileSync(filePath);

    const formData = new FormData();
    formData.append('file', new Blob([file]), filePath);
    formData.append('description', description);

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
      },
      body: formData
    });

    if (!response.ok) {
      console.error(`Couldn't upload media: ${response.status} ${response.statusText}`);
    }

    const data = await response.json();
    return data.id;
  } catch (error) {
    console.error('Error during media upload:', error);
    throw error;
  }
}

